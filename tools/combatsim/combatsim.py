#!/usr/bin/env python3
"""
combatsim: batched Minecraft (Java 1.21.x / 26.3) combat simulator and trainer for the autoCraft bot.

Thousands of fights run at once as tensors (CUDA when there is a GPU). It makes two things for the mod:
  * a combat policy: a small MLP choosing, every tick of a melee fight, how to move, whether to sprint, jump,
    swing or raise the shield. It is trained with evolution strategies, with the bot's hand-written rules
    (RulePolicy, a port of CombatBehavior.fight) as the bar to beat;
  * ThreatNet weights: the mod's 12-input "is fighting this a good idea" net, fitted to the outcomes of
    simulated fights instead of a hand-written teacher.

Numbers come from minecraft.wiki and mcpk.wiki (cited next to each constant). Where the wiki was unclear, the 26.3
game code decided: Mob.setSpeed also sets the forward input to the speed, so a mob accelerates by speed² per tick
and chases at speed²/(1-0.546) blocks/tick (zombie 2.33 m/s, slower than a walking player).

Usage, from the repo root (the venv has a CUDA 12.6 torch that still supports the GTX 1080 Ti):
  tools/combatsim/.venv/Scripts/python tools/combatsim/combatsim.py bench
  tools/combatsim/.venv/Scripts/python tools/combatsim/combatsim.py train --gens 3000
  tools/combatsim/.venv/Scripts/python tools/combatsim/combatsim.py threatnet
  tools/combatsim/.venv/Scripts/python tools/combatsim/combatsim.py eval
Checkpoints and logs go to tools/combatsim/out/. The weights the mod loads go to src/main/resources/assets/autocraft/.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import time
from dataclasses import dataclass

import torch

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "out")
ASSETS = os.path.normpath(os.path.join(ROOT, "..", "..", "src", "main", "resources", "assets", "autocraft"))

# ---------------------------------------------------------------------------------------------------------------
# Numbers. Units: blocks, ticks (20 per second), half-hearts (HP).
# ---------------------------------------------------------------------------------------------------------------
# Player movement, https://www.mcpk.wiki/wiki/Horizontal_Movement_Formulas: ground V = 0.546*V + 0.1*M*0.98,
# air V = 0.91*V + 0.02*M*0.98, M = 1.3 sprinting / 1.0 walking; item use (shield up) multiplies input by 0.2
# (https://minecraft.wiki/w/Shield). Jump vy = 0.42, then vy = (vy - 0.08)*0.98 (https://minecraft.wiki/w/Entity);
# a sprint-jump adds 0.2 along the facing.
GROUND_DRAG = 0.546
AIR_DRAG = 0.91
GROUND_ACCEL = 0.1 * 0.98
AIR_ACCEL = 0.02 * 0.98
SPRINT_MULT = 1.3
USE_ITEM_MULT = 0.2
JUMP_VY = 0.42
GRAVITY = 0.08
VERTICAL_DRAG = 0.98
SPRINT_JUMP_BOOST = 0.2
PLAYER_HALF_W = 0.3
PLAYER_H = 1.8
EYE = 1.62
# Melee, https://minecraft.wiki/w/Melee_attack: reach 3 (eye to the target's box); damage x (0.2 + 0.8 p^2) with
# p = clamp((t + 0.5)/T); crits need p >= 0.848, falling and not sprinting (x1.5); swords T = 20/1.6 = 12.5 ticks.
REACH = 3.0
CRIT_MIN = 0.848
CRIT_MULT = 1.5
SWORD_T = 12.5
# Damage, https://minecraft.wiki/w/Damage: a hit sets a 20-tick timer; while it is above 10 only a bigger hit gets
# through, for the difference, and without knockback. Armor, https://minecraft.wiki/w/Armor.
HURT_TIMER = 20
HURT_OPEN = 10
# Knockback, https://minecraft.wiki/w/Knockback_(mechanic): v.xz = v.xz/2 - dir*s; on the ground v.y = min(0.4, v.y/2 + s).
KB_HIT = 0.4
KB_SPRINT_EXTRA = 0.5
KB_SHIELD_ATTACKER = 0.5  # LivingEntity.blockUsingShield (wiki unclear for Java; kept from the code)
# Shield, https://minecraft.wiki/w/Shield: blocks after 5 ticks raised, the whole front half (180 degrees).
SHIELD_DELAY = 5
# Mob melee, https://minecraft.wiki/w/Melee_attack: the mob's box inflated horizontally only, by sqrt(2.04)-0.6;
# MeleeAttackGoal waits 20 ticks between hits.
MOB_REACH_INFLATE = math.sqrt(2.04) - 0.6
MOB_ATTACK_COOLDOWN = 20
# Skeletons (RangedBowAttackGoal; https://minecraft.wiki/w/Skeleton): range 15, draw 20 ticks, 40 ticks between shots
# on Normal; strafe backwards inside 7.5 blocks, sideways otherwise, flipping 30% of the time every 20 ticks.
# Arrows, https://minecraft.wiki/w/Arrow: launched at 1.6, aimed with +0.2/block of distance, inaccuracy 14-4*2 = 6,
# drag 0.99 then gravity 0.05 per tick, damage ceil(|v| * (2 + 0.11*2 + gauss*0.25)).
BOW_RANGE = 15.0
BOW_DRAW = 20
BOW_INTERVAL = 40
ARROW_SPEED = 1.6
ARROW_INACCURACY = 6.0
ARROW_DRAG = 0.99
ARROW_GRAVITY = 0.05
# Creepers, https://minecraft.wiki/w/Creeper and /w/Explosion: fuse 30 ticks, starts within 3 blocks, gives up
# beyond 7; power 3: impact = 1 - d/6, damage = (impact^2 + impact)/2 * 7 * 6 + 1 (open ground: exposure 1).
FUSE = 30
SWELL_START = 3.0
SWELL_CANCEL = 7.0
BLAST_RADIUS = 6.0
# Spiders (LeapAtTargetGoal 0.4): 2-4 blocks away, 1 in 5 per tick, jump 0.4 up and 0.4 toward the target.

NONE, ZOMBIE, SKELETON, CREEPER, SPIDER, HOGLIN, PIGLIN, XBOW_PIGLIN = 0, 1, 2, 3, 4, 5, 6, 7
TYPE_NAMES = ["none", "zombie", "skeleton", "creeper", "spider", "hoglin", "piglin", "crossbow piglin"]
# The Nether mobs (harder fights) act and are seen as their class — melee, archer — plus their health, exactly as the
# mod's CombatObs sees them: a hoglin is "melee" at 40/20 health, a crossbow piglin an archer.
#            none  zombie skel  creeper spider hoglin piglin xbow  (https://minecraft.wiki/w/Zombie, /Skeleton, /Creeper,
#                                                                    /Spider, /Hoglin, /Piglin)
MOB_HP = [0.0, 20.0, 20.0, 20.0, 16.0, 40.0, 16.0, 16.0]
MOB_DMG = [0.0, 3.0, 2.0, 0.0, 2.0, 6.0, 8.0, 0.0]  # Normal difficulty; a piglin's golden sword is +3
MOB_SPEED = [0.0, 0.23, 0.25, 0.25, 0.3, 0.3, 0.35, 0.35]
MOB_HALF_W = [0.3, 0.3, 0.3, 0.3, 0.7, 0.7, 0.3, 0.3]
MOB_H = [1.95, 1.95, 1.99, 1.7, 0.9, 1.4, 1.95, 1.95]
MOB_ARMOR = [0.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
MOB_CLASS = [NONE, ZOMBIE, SKELETON, CREEPER, SPIDER, ZOMBIE, ZOMBIE, SKELETON]
MOB_KB = [1.0, 1.0, 1.0, 1.0, 1.0, 2.2, 1.0, 1.0]  # hit knockback: a hoglin throws you
ARROW_BASE = [0.0, 0.0, 2.22, 0.0, 0.0, 0.0, 0.0, 2.7]  # arrow base damage: skeleton bow (Normal), piglin crossbow
DANGER = [0.0, 1.0, 1.5, 2.5, 1.2, 2.5, 2.0, 1.8]  # Threats.danger in the mod (zombie = 1)

ARENA = 14.0  # half size of the square arena
MAX_MOBS = 6
MAX_ARROWS = 6
K_OBS = 4  # nearest mobs in the observation
T_MAX = 1200  # 60 s per fight at most

OBS = 9 + K_OBS * 13 + 4 + 4 + 1
MOVES = 9  # none, F, B, L, R, FL, FR, BL, BR (relative to facing the target)
OUTS = MOVES + 4  # + sprint, jump, attack, shield (one logit each, > 0 = yes)
HIDDEN = 64
MOVE_DIRS = [(0, 0), (1, 0), (-1, 0), (0, -1), (0, 1), (1, -1), (1, 1), (-1, -1), (-1, 1)]  # (forward, right)


# ---------------------------------------------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------------------------------------------
@dataclass
class Scenarios:
    """A batch of fight setups (CPU tensors; the sim copies them to its device)."""
    mtype: torch.Tensor  # [N, MAX_MOBS] long
    mx: torch.Tensor
    mz: torch.Tensor
    hp: torch.Tensor  # [N]
    armor: torch.Tensor
    tough: torch.Tensor
    wdmg: torch.Tensor
    shield: torch.Tensor  # bool
    food: torch.Tensor  # ThreatNet input only (not simulated)
    dark: torch.Tensor  # ThreatNet input only

    def __len__(self):
        return self.hp.shape[0]

    def repeat(self, times: int) -> "Scenarios":
        """Each scenario `times` times in a row: [s0 s1 ...] -> [s0 s1 ... s0 s1 ...] (population-major)."""
        rep = lambda t: t.repeat(times, *([1] * (t.dim() - 1)))
        return Scenarios(*(rep(getattr(self, f)) for f in self.__dataclass_fields__))

    def select(self, idx: torch.Tensor) -> "Scenarios":
        return Scenarios(*(getattr(self, f)[idx] for f in self.__dataclass_fields__))


def sample_scenarios(n: int, seed: int, max_mobs: int = 5, hard: bool = False) -> Scenarios:
    """
    Random fights. hard: two to four mobs, Nether mobs among them (hoglins, piglins), surrounded more often, closer in,
    lower health and worse gear — about twice the rules' deaths of the usual mix (six of them killed it 80% of the time).
    """
    g = torch.Generator().manual_seed(seed)
    rnd = lambda *s: torch.rand(*s, generator=g)
    if hard:
        count = 2 + (rnd(n) * 3).long().clamp(max=2)
        p = torch.tensor([0.0, 0.24, 0.16, 0.10, 0.12, 0.14, 0.12, 0.12])
    else:
        count = 1 + (rnd(n) * max_mobs).long().clamp(max=max_mobs - 1)
        # mob mix: zombies (and husks/drowned) most common, then skeletons, spiders, creepers
        p = torch.tensor([0.0, 0.45, 0.22, 0.13, 0.20, 0.0, 0.0, 0.0])
    kinds = torch.multinomial(p.expand(n * MAX_MOBS, len(p)), 1, generator=g).view(n, MAX_MOBS)
    slot = torch.arange(MAX_MOBS).expand(n, MAX_MOBS)
    mtype = torch.where(slot < count.unsqueeze(1), kinds, torch.zeros_like(kinds))
    ang = rnd(n, MAX_MOBS) * 2 * math.pi
    # one group (all within 120 degrees) most of the time, all round otherwise
    grouped = rnd(n) < (0.45 if hard else 0.6)
    base = rnd(n, 1) * 2 * math.pi
    ang = torch.where(grouped.unsqueeze(1), base + (rnd(n, MAX_MOBS) - 0.5) * (2 * math.pi / 3), ang)
    dist = (3.5 + rnd(n, MAX_MOBS) * 9.0) if hard else (4.0 + rnd(n, MAX_MOBS) * 10.0)
    mx = (torch.cos(ang) * dist).clamp(-ARENA + 1, ARENA - 1)
    mz = (torch.sin(ang) * dist).clamp(-ARENA + 1, ARENA - 1)
    hp = torch.where(rnd(n) < (0.5 if hard else 0.6), torch.full((n,), 20.0), (8.0 if hard else 5.0) + rnd(n) * (12.0 if hard else 15.0)).round()
    armor_choices = torch.tensor([0.0, 2.0, 8.0, 15.0, 20.0])
    armor_p = torch.tensor([0.4, 0.2, 0.2, 0.15, 0.05] if hard else [0.35, 0.15, 0.2, 0.2, 0.1])
    ai = torch.multinomial(armor_p.expand(n, 5), 1, generator=g).squeeze(1)
    armor = armor_choices[ai]
    tough = torch.where(ai == 4, torch.full((n,), 8.0), torch.zeros(n))
    weapons = torch.tensor([4.0, 5.0, 6.0, 7.0])
    weapon_p = torch.tensor([0.2, 0.4, 0.3, 0.1] if hard else [0.1, 0.4, 0.35, 0.15])
    wdmg = weapons[torch.multinomial(weapon_p.expand(n, 4), 1, generator=g).squeeze(1)]
    shield = rnd(n) < (0.55 if hard else 0.65)
    food = rnd(n)
    dark = rnd(n) < 0.5
    return Scenarios(mtype, mx, mz, hp, armor, tough, wdmg, shield, food, dark)


def sample_mix(n: int, seed: int, mix: float) -> Scenarios:
    """n fights: the usual ones, and a `mix` share of hard ones (kept apart from the usual: no forgetting those)."""
    k = int(round(n * mix))
    if k <= 0:
        return sample_scenarios(n, seed)
    if k >= n:
        return sample_scenarios(n, seed, hard=True)
    a, b = sample_scenarios(n - k, seed), sample_scenarios(k, seed + 7919, hard=True)
    return Scenarios(*(torch.cat([getattr(a, f), getattr(b, f)]) for f in Scenarios.__dataclass_fields__))


# ---------------------------------------------------------------------------------------------------------------
# Simulator
# ---------------------------------------------------------------------------------------------------------------
class Sim:
    """
    B fights at once: one player at the arena's middle and up to MAX_MOBS mobs each, on open flat ground.
    Every state tensor is a fixed buffer: once built, assigning to one copies into it (see __setattr__), so a tick
    can be captured as a CUDA graph and replayed — hundreds of tiny kernels launched as one.
    """

    def __setattr__(self, name, value):
        cur = self.__dict__.get(name)
        if self.__dict__.get("_frozen") and isinstance(cur, torch.Tensor) and isinstance(value, torch.Tensor) and cur.shape == value.shape:
            cur.copy_(value)
        else:
            object.__setattr__(self, name, value)

    def __init__(self, n: int, device: torch.device):
        self.dev = device
        self.B = B = n
        f = dict(device=device, dtype=torch.float32)
        tbl = lambda v: torch.tensor(v, **f)
        self.T_HP, self.T_DMG, self.T_SPEED = tbl(MOB_HP), tbl(MOB_DMG), tbl(MOB_SPEED)
        self.T_HW, self.T_H, self.T_ARMOR = tbl(MOB_HALF_W), tbl(MOB_H), tbl(MOB_ARMOR)
        self.T_KB, self.T_ARROW = tbl(MOB_KB), tbl(ARROW_BASE)
        self.T_CLASS = torch.tensor(MOB_CLASS, device=device, dtype=torch.long)
        self.T_MOVES = torch.tensor(MOVE_DIRS, **f)
        self.EYE_MOBS = torch.eye(MAX_MOBS, device=device, dtype=torch.bool)
        self.ARANGE_M = torch.arange(MAX_MOBS, device=device)
        self.ARANGE_A = torch.arange(MAX_ARROWS, device=device)
        z = lambda *s: torch.zeros(*s, **f)
        zb = lambda *s: torch.zeros(*s, device=device, dtype=torch.bool)
        # player
        self.px, self.pz, self.py, self.vx, self.vz, self.vy, self.yaw = (z(B) for _ in range(7))
        self.hp, self.start_hp, self.armor, self.tough, self.wdmg = (z(B) for _ in range(5))
        self.has_shield, self.sprinting, self.alive, self.done = (zb(B) for _ in range(4))
        self.atk_t, self.shield_t, self.hurt_t, self.last_hurt, self.t, self.dmg_taken, self.kills = (z(B) for _ in range(7))
        self.dmg_dealt, self.mob_hp_total = z(B), z(B)
        # mobs
        self.mtype = torch.zeros(B, MAX_MOBS, device=device, dtype=torch.long)
        self.mclass = torch.zeros(B, MAX_MOBS, device=device, dtype=torch.long)  # how it acts: MOB_CLASS of its type
        self.malive, self.mback = zb(B, MAX_MOBS), zb(B, MAX_MOBS)
        self.n_mobs = z(B)
        (self.mx, self.mz, self.my, self.mvx, self.mvz, self.mvy, self.mhp, self.mhurt_t, self.mlast, self.matk_cd, self.mdraw,
         self.mshot_cd, self.mstrafe, self.mstrafe_t, self.mswell) = (z(B, MAX_MOBS) for _ in range(15))
        # arrows
        self.aon = zb(B, MAX_ARROWS)
        self.ax, self.ay, self.az, self.avx, self.avy, self.avz, self.abase = (z(B, MAX_ARROWS) for _ in range(7))
        self.target = torch.zeros(B, device=device, dtype=torch.long)
        self._frozen = True

    def load(self, scen: Scenarios, seed: int = 0):
        """Start a new batch of fights (copied into the buffers; the captured graph keeps working)."""
        assert len(scen) == self.B
        torch.manual_seed(seed)
        d = lambda t: t.to(self.dev)
        for name in ("px", "pz", "py", "vx", "vz", "vy", "yaw", "shield_t", "hurt_t", "last_hurt", "t", "dmg_taken", "kills", "dmg_dealt"):
            getattr(self, name).zero_()
        self.hp = d(scen.hp).float()
        self.start_hp = self.hp
        self.armor = d(scen.armor).float()
        self.tough = d(scen.tough).float()
        self.wdmg = d(scen.wdmg).float()
        self.has_shield = d(scen.shield).bool()
        self.atk_t.fill_(20.0)  # ready at the start
        self.sprinting.zero_()
        self.alive.fill_(True)
        self.done.zero_()
        self.mtype = d(scen.mtype).long()
        self.mclass = self.T_CLASS[self.mtype]
        self.malive = self.mtype > 0
        self.n_mobs = self.malive.sum(1).float()
        self.mx = d(scen.mx).float()
        self.mz = d(scen.mz).float()
        for name in ("my", "mvx", "mvz", "mvy", "mhurt_t", "mlast", "matk_cd", "mdraw", "mstrafe_t", "mswell", "ax", "ay", "az", "avx", "avy", "avz", "abase"):
            getattr(self, name).zero_()
        self.mback.zero_()
        self.aon.zero_()
        self.mhp = self.T_HP[self.mtype] * self.malive
        self.mob_hp_total = self.mhp.sum(1)
        self.mshot_cd = self.rand(self.B, MAX_MOBS) * BOW_INTERVAL
        self.mstrafe = torch.where(self.rand(self.B, MAX_MOBS) < 0.5, 1.0, -1.0)
        self._update_target()
        return self

    # -- helpers ------------------------------------------------------------------------------------------------
    def rand(self, *shape):
        return torch.rand(*shape, device=self.dev)  # default generator: safe inside a CUDA graph

    def randn(self, *shape):
        return torch.randn(*shape, device=self.dev)

    def onehot_m(self, idx):
        return idx.unsqueeze(1) == self.ARANGE_M  # F.one_hot syncs with the CPU (checks its input): no good in a graph

    def mob_dist(self):
        dx = self.mx - self.px.unsqueeze(1)
        dz = self.mz - self.pz.unsqueeze(1)
        return dx, dz, torch.sqrt(dx * dx + dz * dz + 1e-9)

    def _update_target(self):
        """Nearest living mob; creepers only when nothing else is left (pickTarget in the mod)."""
        _, _, dist = self.mob_dist()
        creeper = self.mtype == CREEPER
        others = (self.malive & ~creeper).any(1, keepdim=True)
        key = dist + (~self.malive) * 1e4 + (creeper & others) * 1e3
        self.target = key.argmin(1)

    def gather(self, t, idx):
        return t.gather(1, idx.unsqueeze(1)).squeeze(1)

    def armor_reduce(self, dmg):
        a, tough = self.armor, self.tough
        eff = torch.clamp(torch.maximum(a / 5, a - 4 * dmg / (tough + 8)), max=20.0)
        return dmg * (1 - eff / 25)

    def hurt_player(self, dmg, from_x, from_z, kb, blockable):
        """Damage (per env, 0 = none) from a source at (from_x, from_z); returns where the shield blocked it."""
        hit = (dmg > 0) & self.alive
        to_src_x, to_src_z = from_x - self.px, from_z - self.pz
        facing = torch.cos(self.yaw) * to_src_x + torch.sin(self.yaw) * to_src_z
        blocked = hit & blockable & (self.shield_t >= SHIELD_DELAY) & (facing > 0)
        hit = hit & ~blocked
        dmg = self.armor_reduce(dmg)
        open_ = self.hurt_t <= HURT_OPEN
        taken = torch.where(open_, dmg, torch.clamp(dmg - self.last_hurt, min=0.0)) * hit
        full = hit & open_
        self.last_hurt = torch.where(full | (hit & (dmg > self.last_hurt)), dmg, self.last_hurt)
        self.hurt_t = torch.where(full, torch.full_like(self.hurt_t, HURT_TIMER), self.hurt_t)
        self.hp = self.hp - taken
        self.dmg_taken = self.dmg_taken + taken
        # knockback away from the source (only for hits that opened the timer)
        n = torch.sqrt(to_src_x ** 2 + to_src_z ** 2 + 1e-9)
        k = kb * full
        on_ground = self.py <= 0
        self.vx = torch.where(full, self.vx / 2 - to_src_x / n * k, self.vx)
        self.vz = torch.where(full, self.vz / 2 - to_src_z / n * k, self.vz)
        self.vy = torch.where(full & on_ground, torch.clamp(self.vy / 2 + k, max=0.4), self.vy)
        return blocked

    def hurt_mobs(self, dmg, from_x, from_z, kb):
        """dmg [B, M]; knockback strength kb [B, M] away from (from_x, from_z) [B]."""
        hit = (dmg > 0) & self.malive
        a = self.T_ARMOR[self.mtype]
        dmg = dmg * (1 - torch.clamp(torch.maximum(a / 5, a - 4 * dmg / 8), max=20.0) / 25)
        open_ = self.mhurt_t <= HURT_OPEN
        taken = torch.where(open_, dmg, torch.clamp(dmg - self.mlast, min=0.0)) * hit
        full = hit & open_
        self.mlast = torch.where(full | (hit & (dmg > self.mlast)), dmg, self.mlast)
        self.mhurt_t = torch.where(full, torch.full_like(self.mhurt_t, HURT_TIMER), self.mhurt_t)
        # damage dealt (no overkill) counts for the player only while it's alive: progress toward kills, for the reward
        self.dmg_dealt = self.dmg_dealt + (torch.minimum(taken, torch.clamp(self.mhp, min=0.0)) * (self.mtype > 0)).sum(1) * self.alive
        self.mhp = self.mhp - taken
        dx = self.mx - from_x.unsqueeze(1)
        dz = self.mz - from_z.unsqueeze(1)
        n = torch.sqrt(dx * dx + dz * dz + 1e-9)
        k = kb * full
        self.mvx = torch.where(full, self.mvx / 2 + dx / n * k, self.mvx)
        self.mvz = torch.where(full, self.mvz / 2 + dz / n * k, self.mvz)
        self.mvy = torch.where(full & (self.my <= 0), torch.clamp(self.mvy / 2 + k, max=0.4), self.mvy)
        died = self.malive & (self.mhp <= 0)
        self.kills = self.kills + (died & self.alive.unsqueeze(1)).sum(1)
        self.malive = self.malive & ~died

    # -- one tick -----------------------------------------------------------------------------------------------
    def step(self, move, sprint, jump, attack, shield):
        """Actions per env: move [B] long in 0..8, the rest [B] bool."""
        live = ~self.done
        self._update_target()
        tgt = self.target
        dx, dz, dist = self.mob_dist()
        tdx, tdz = self.gather(dx, tgt), self.gather(dz, tgt)
        self.yaw = torch.where(live, torch.atan2(tdz, tdx), self.yaw)
        fx, fz = torch.cos(self.yaw), torch.sin(self.yaw)

        # shield and swing: a swing needs the shield down since last tick (Java won't attack with it up)
        attack = attack & live
        shield = shield & self.has_shield & live & ~attack
        can_swing = attack & (self.shield_t == 0)
        self.shield_t = torch.where(shield, self.shield_t + 1, torch.zeros_like(self.shield_t))
        p = torch.clamp((self.atk_t + 0.5) / SWORD_T, 0, 1)
        # reach: eye to the closest point of the target's box
        tmt = self.gather(self.mtype, tgt)
        thw, th = self.T_HW[tmt], self.T_H[tmt]
        tmx, tmz, tmy = self.gather(self.mx, tgt), self.gather(self.mz, tgt), self.gather(self.my, tgt)
        cx = torch.maximum(torch.minimum(self.px, tmx + thw), tmx - thw) - self.px
        cz = torch.maximum(torch.minimum(self.pz, tmz + thw), tmz - thw) - self.pz
        eye = self.py + EYE
        cy = torch.maximum(torch.minimum(eye, tmy + th), tmy) - eye
        in_reach = (cx * cx + cy * cy + cz * cz) < REACH * REACH
        t_alive = self.gather(self.malive, tgt)
        swing_hits = can_swing & in_reach & t_alive
        falling = (self.py > 0) & (self.vy < 0)
        crit = swing_hits & (p >= CRIT_MIN) & falling & ~self.sprinting
        sprint_hit = swing_hits & (p >= CRIT_MIN) & self.sprinting
        dmg = self.wdmg * (0.2 + 0.8 * p * p) * torch.where(crit, CRIT_MULT, 1.0) * swing_hits
        kb = KB_HIT + KB_SPRINT_EXTRA * sprint_hit
        onehot = self.onehot_m(tgt).float()
        self.hurt_mobs(dmg.unsqueeze(1) * onehot, self.px, self.pz, kb.unsqueeze(1) * onehot)
        self.atk_t = torch.where(can_swing, torch.zeros_like(self.atk_t), self.atk_t + 1)
        self.vx = torch.where(sprint_hit, self.vx * 0.6, self.vx)
        self.vz = torch.where(sprint_hit, self.vz * 0.6, self.vz)
        self.sprinting = self.sprinting & ~sprint_hit

        # player movement
        dirs = self.T_MOVES[move]
        fw, rt = dirs[:, 0], dirs[:, 1]
        norm = torch.clamp(torch.sqrt(fw * fw + rt * rt), min=1.0)
        fw, rt = fw / norm, rt / norm
        ix = fw * fx - rt * fz
        iz = fw * fz + rt * fx
        using = self.shield_t > 0
        self.sprinting = sprint & (fw > 0) & ~using & live
        mult = torch.where(self.sprinting, SPRINT_MULT, 1.0) * torch.where(using, USE_ITEM_MULT, 1.0)
        on_ground = self.py <= 0
        accel = torch.where(on_ground, GROUND_ACCEL, AIR_ACCEL) * mult
        drag = torch.where(on_ground, GROUND_DRAG, AIR_DRAG)
        do_jump = jump & on_ground & live
        boost = (do_jump & self.sprinting) * SPRINT_JUMP_BOOST
        self.vx = (self.vx * drag + ix * accel + fx * boost) * live
        self.vz = (self.vz * drag + iz * accel + fz * boost) * live
        self.vy = torch.where(do_jump, torch.full_like(self.vy, JUMP_VY), self.vy)
        self.px = self.px + self.vx
        self.pz = self.pz + self.vz
        self.py = self.py + self.vy
        self.vy = (self.vy - GRAVITY) * VERTICAL_DRAG
        landed = self.py <= 0
        self.py = torch.where(landed, torch.zeros_like(self.py), self.py)
        self.vy = torch.where(landed, torch.zeros_like(self.vy), self.vy)
        lim = ARENA - PLAYER_HALF_W
        hitx = self.px.abs() > lim
        hitz = self.pz.abs() > lim
        self.px = self.px.clamp(-lim, lim)
        self.pz = self.pz.clamp(-lim, lim)
        self.vx = torch.where(hitx, torch.zeros_like(self.vx), self.vx)
        self.vz = torch.where(hitz, torch.zeros_like(self.vz), self.vz)

        self._mobs(live)
        self._arrows(live)
        self._push()  # crowding: mobs push each other and the player apart

        self.hurt_t = torch.clamp(self.hurt_t - 1, min=0)
        self.mhurt_t = torch.clamp(self.mhurt_t - 1, min=0)
        self.alive = self.alive & (self.hp > 0)
        self.t = self.t + live
        self.done = self.done | ~self.alive | ~self.malive.any(1) | (self.t >= T_MAX)

    def _mobs(self, live):
        dx, dz, dist = self.mob_dist()
        ux, uz = dx / dist, dz / dist  # player -> mob; the mob walks along (-ux, -uz)
        mt = self.mtype
        speed = self.T_SPEED[mt]
        on_ground = self.my <= 0
        alive = self.malive & live.unsqueeze(1)
        mc = self.mclass
        zombie_like = (mc == ZOMBIE) | (mc == SPIDER)
        creeper = mc == CREEPER
        skel = mc == SKELETON
        # creeper fuse: lights within 3, keeps burning within 7, cools down beyond; stands still while lit
        swelling = creeper & alive & ((dist < SWELL_START) | ((self.mswell > 0) & (dist <= SWELL_CANCEL)))
        cooled = torch.where(swelling, self.mswell + 1, torch.clamp(self.mswell - 1, min=0))
        self.mswell = torch.where(creeper & alive, cooled, self.mswell)
        # skeletons: walk in until 15 blocks, then strafe (backwards inside 7.5); bow every 40 ticks after a 20-tick draw
        self.mback = (dist < BOW_RANGE * 0.5) | (self.mback & (dist <= BOW_RANGE * math.sqrt(0.75)))
        self.mstrafe_t = torch.where(skel & (dist < BOW_RANGE), self.mstrafe_t + 1, torch.zeros_like(self.mstrafe_t))
        flip = skel & (self.mstrafe_t >= 20) & (self.rand(*dist.shape) < 0.3)
        self.mstrafe = torch.where(flip, -self.mstrafe, self.mstrafe)
        self.mstrafe_t = torch.where(self.mstrafe_t >= 20, torch.zeros_like(self.mstrafe_t), self.mstrafe_t)
        strafing = skel & (dist < BOW_RANGE)
        fwd = torch.where(strafing, torch.where(self.mback, -0.5, 0.5), 1.0)
        side = torch.where(strafing, self.mstrafe * 0.5, 0.0)
        wx = fwd * (-ux) + side * uz
        wz = fwd * (-uz) - side * ux
        moving = alive & ~swelling
        accel = speed * speed * moving * on_ground  # Mob.setSpeed: input = speed, scaled by speed again
        mdrag = torch.where(on_ground, GROUND_DRAG, AIR_DRAG)
        self.mvx = self.mvx * mdrag + wx * accel
        self.mvz = self.mvz * mdrag + wz * accel
        # spider leap
        leap = (mt == SPIDER) & alive & on_ground & (dist > 2) & (dist < 4) & (self.rand(*dist.shape) < 0.2)
        self.mvx = torch.where(leap, -ux * 0.4 + self.mvx * 0.2, self.mvx)
        self.mvz = torch.where(leap, -uz * 0.4 + self.mvz * 0.2, self.mvz)
        self.mvy = torch.where(leap, torch.full_like(self.mvy, 0.4), self.mvy)
        self.mx = self.mx + self.mvx * alive
        self.mz = self.mz + self.mvz * alive
        self.my = self.my + self.mvy * alive
        self.mvy = (self.mvy - GRAVITY) * VERTICAL_DRAG
        grounded = self.my <= 0
        self.my = torch.where(grounded, torch.zeros_like(self.my), self.my)
        self.mvy = torch.where(grounded, torch.zeros_like(self.mvy), self.mvy)
        lim = ARENA - self.T_HW[mt]
        self.mx = torch.maximum(torch.minimum(self.mx, lim), -lim)
        self.mz = torch.maximum(torch.minimum(self.mz, lim), -lim)

        # melee: attack box (inflated horizontally) against the player's box
        dx, dz, dist = self.mob_dist()
        hw = self.T_HW[mt] + MOB_REACH_INFLATE + PLAYER_HALF_W
        vert = (self.my < (self.py + PLAYER_H).unsqueeze(1)) & ((self.my + self.T_H[mt]) > self.py.unsqueeze(1))
        touching = (dx.abs() < hw) & (dz.abs() < hw) & vert
        self.matk_cd = torch.clamp(self.matk_cd - 1, min=0)
        swings = zombie_like & alive & touching & (self.matk_cd <= 0)
        self.matk_cd = torch.where(swings, torch.full_like(self.matk_cd, MOB_ATTACK_COOLDOWN), self.matk_cd)
        # one hit per tick reaches the player (the hurt timer would eat the rest): the strongest swing
        dmg = self.T_DMG[mt] * swings
        best = dmg.argmax(1)
        bdmg = self.gather(dmg, best)
        bx, bz = self.gather(self.mx, best), self.gather(self.mz, best)
        kb_hit = self.gather(self.T_KB[mt], best) * KB_HIT
        blocked = self.hurt_player(bdmg, bx, bz, kb_hit, torch.ones_like(self.alive))
        # a blocked hit knocks the attacker back
        kb = blocked.float().unsqueeze(1) * self.onehot_m(best).float() * KB_SHIELD_ATTACKER
        self.mvx = self.mvx + dx / dist * kb
        self.mvz = self.mvz + dz / dist * kb

        # bows
        draws = skel & alive & (self.mdraw > 0)
        self.mdraw = torch.where(draws, self.mdraw + 1, self.mdraw)
        shoot = draws & (self.mdraw >= BOW_DRAW)
        self.mdraw = torch.where(shoot, torch.zeros_like(self.mdraw), self.mdraw)
        self.mshot_cd = torch.where(shoot, torch.full_like(self.mshot_cd, BOW_INTERVAL), torch.clamp(self.mshot_cd - 1, min=0))
        start = skel & alive & (self.mdraw == 0) & ~shoot & (self.mshot_cd <= 0) & (dist < BOW_RANGE)
        self.mdraw = torch.where(start, torch.ones_like(self.mdraw), self.mdraw)
        self._spawn_arrows(shoot)

        # creepers that burnt down
        self._explode(creeper & alive & (self.mswell >= FUSE))

    def _spawn_arrows(self, shoot):
        B = self.B
        # one arrow per env per tick at most (the first shooter) into the first free slot
        shooter = shoot.float().argmax(1)
        has = shoot.any(1) & (~self.aon).any(1)
        free = (~self.aon).float().argmax(1)
        sx, sz = self.gather(self.mx, shooter), self.gather(self.mz, shooter)
        sy = self.gather(self.my, shooter) + 1.64
        tx, tz = self.px - sx, self.pz - sz
        ty = self.py + PLAYER_H / 3 - sy
        horiz = torch.sqrt(tx * tx + tz * tz + 1e-9)
        vx, vy, vz = tx, ty + horiz * 0.2, tz
        n = torch.sqrt(vx * vx + vy * vy + vz * vz + 1e-9)
        spread = 0.0172275 * ARROW_INACCURACY
        tri = lambda: (self.rand(B) - self.rand(B)) * spread
        vx, vy, vz = (vx / n + tri()) * ARROW_SPEED, (vy / n + tri()) * ARROW_SPEED, (vz / n + tri()) * ARROW_SPEED
        base = self.gather(self.T_ARROW[self.mtype], shooter) + self.randn(B) * 0.25
        oh = (free.unsqueeze(1) == self.ARANGE_A) & has.unsqueeze(1)
        put = lambda dst, val: torch.where(oh, val.unsqueeze(1), dst)
        self.ax, self.ay, self.az = put(self.ax, sx), put(self.ay, sy), put(self.az, sz)
        self.avx, self.avy, self.avz = put(self.avx, vx), put(self.avy, vy), put(self.avz, vz)
        self.abase = put(self.abase, base)
        self.aon = self.aon | oh

    def _arrows(self, live):
        on = self.aon & live.unsqueeze(1)
        x0, y0, z0 = self.ax, self.ay, self.az
        dx, dy, dz = self.avx, self.avy, self.avz
        # swept test against the player's box inflated by 0.3 (ProjectileUtil.getEntityHitResult)
        lo_x, hi_x = (self.px - PLAYER_HALF_W - 0.3).unsqueeze(1), (self.px + PLAYER_HALF_W + 0.3).unsqueeze(1)
        lo_y, hi_y = (self.py - 0.3).unsqueeze(1), (self.py + PLAYER_H + 0.3).unsqueeze(1)
        lo_z, hi_z = (self.pz - PLAYER_HALF_W - 0.3).unsqueeze(1), (self.pz + PLAYER_HALF_W + 0.3).unsqueeze(1)

        def slab(o, d, lo, hi):
            d = torch.where(d.abs() < 1e-7, torch.full_like(d, 1e-7), d)
            t1, t2 = (lo - o) / d, (hi - o) / d
            return torch.minimum(t1, t2), torch.maximum(t1, t2)

        a1, b1 = slab(x0, dx, lo_x, hi_x)
        a2, b2 = slab(y0, dy, lo_y, hi_y)
        a3, b3 = slab(z0, dz, lo_z, hi_z)
        tmin = torch.maximum(torch.maximum(a1, a2), a3)
        tmax = torch.minimum(torch.minimum(b1, b2), b3)
        hit = on & (tmax >= torch.clamp(tmin, min=0)) & (tmin <= 1)
        speed = torch.sqrt(dx * dx + dy * dy + dz * dz)
        dmg = torch.ceil(speed * self.abase) * hit
        # at most one arrow lands per tick (hurt timer): the strongest
        best = dmg.argmax(1)
        bdmg = self.gather(dmg, best)
        # the source direction is where the arrow came from
        bx = self.px - self.gather(dx, best)
        bz = self.pz - self.gather(dz, best)
        self.hurt_player(bdmg, bx, bz, KB_HIT, torch.ones_like(self.alive))
        # blocked or not, an arrow that reached the player is gone
        self.aon = self.aon & ~hit
        self.ax, self.ay, self.az = x0 + dx, y0 + dy, z0 + dz
        self.avx, self.avy, self.avz = dx * ARROW_DRAG, dy * ARROW_DRAG - ARROW_GRAVITY, dz * ARROW_DRAG
        gone = (self.ay < 0) | (self.ax.abs() > ARENA + 2) | (self.az.abs() > ARENA + 2)
        self.aon = self.aon & ~gone

    def _explode(self, boom):
        # at most one creeper per env per tick (another goes next tick)
        which = boom.float().argmax(1)
        has = boom.any(1)
        cx, cz = self.gather(self.mx, which), self.gather(self.mz, which)
        dpx, dpz = self.px - cx, self.pz - cz
        dp = torch.sqrt(dpx * dpx + dpz * dpz + 1e-9)
        impact = torch.clamp(1 - dp / BLAST_RADIUS, min=0) * has
        dmg = ((impact * impact + impact) / 2 * 7 * BLAST_RADIUS + 1) * (impact > 0)
        blocked = self.hurt_player(dmg, cx, cz, 0.0, torch.ones_like(self.alive))
        kb = impact * ~blocked
        self.vx = self.vx + dpx / dp * kb
        self.vz = self.vz + dpz / dp * kb
        self.vy = self.vy + 0.5 * kb
        # the blast hits the other mobs too (that's the crowd control)
        dmx, dmz = self.mx - cx.unsqueeze(1), self.mz - cz.unsqueeze(1)
        dm = torch.sqrt(dmx * dmx + dmz * dmz + 1e-9)
        mi = torch.clamp(1 - dm / BLAST_RADIUS, min=0) * has.unsqueeze(1)
        self_oh = self.onehot_m(which) & has.unsqueeze(1)
        mdmg = ((mi * mi + mi) / 2 * 7 * BLAST_RADIUS + 1) * (mi > 0) * ~self_oh
        self.hurt_mobs(mdmg, cx, cz, mi)
        self.malive = self.malive & ~self_oh  # the creeper itself (no kill credit)

    def _push(self):
        alive = self.malive
        hw = self.T_HW[self.mtype]
        # mob-mob
        dx = self.mx.unsqueeze(2) - self.mx.unsqueeze(1)
        dz = self.mz.unsqueeze(2) - self.mz.unsqueeze(1)
        d = torch.sqrt(dx * dx + dz * dz + 1e-6)
        reach = hw.unsqueeze(2) + hw.unsqueeze(1)
        over = alive.unsqueeze(2) & alive.unsqueeze(1) & (d < reach) & ~self.EYE_MOBS
        push = 0.05 * over
        self.mvx = self.mvx + (dx / d * push).sum(2)
        self.mvz = self.mvz + (dz / d * push).sum(2)
        # mob-player
        pdx, pdz, pd = self.mob_dist()
        push = 0.05 * (alive & (pd < hw + PLAYER_HALF_W))
        self.mvx = self.mvx + pdx / pd * push
        self.mvz = self.mvz + pdz / pd * push
        self.vx = self.vx - (pdx / pd * push).sum(1)
        self.vz = self.vz - (pdz / pd * push).sum(1)

    # -- what the policy sees (the mod builds exactly this from the real game: CombatObs.java) -------------------
    def observe(self):
        B = self.B
        dx, dz, dist = self.mob_dist()
        fx, fz = torch.cos(self.yaw), torch.sin(self.yaw)
        p = torch.clamp((self.atk_t + 0.5) / SWORD_T, 0, 1)
        on_ground = (self.py <= 0).float()
        self_f = [self.hp / 20, p, (self.shield_t >= SHIELD_DELAY).float(), torch.clamp(self.shield_t, max=SHIELD_DELAY) / SHIELD_DELAY,
                  on_ground, self.vy * 2, self.has_shield.float(), self.armor / 20, self.wdmg / 10]
        key = dist + (~self.malive) * 1e4
        order = key.argsort(1)[:, :K_OBS]
        g = lambda t: t.gather(1, order)
        present = g(self.malive.float())
        mt = g(self.mtype)
        rx, rz = g(dx), g(dz)
        fx1, fz1 = fx.unsqueeze(1), fz.unsqueeze(1)
        fwd = (rx * fx1 + rz * fz1) / 16
        right = (-rx * fz1 + rz * fx1) / 16
        rvx, rvz = g(self.mvx) - self.vx.unsqueeze(1), g(self.mvz) - self.vz.unsqueeze(1)
        vf = (rvx * fx1 + rvz * fz1) * 5
        vr = (-rvx * fz1 + rvz * fx1) * 5
        mc = g(self.mclass)
        timer = torch.where(mc == CREEPER, g(self.mswell) / FUSE, torch.where(mc == SKELETON, (g(self.mdraw) > 0).float(), torch.zeros_like(present)))
        mob_f = torch.stack([present, (mc == ZOMBIE).float(), (mc == SKELETON).float(), (mc == CREEPER).float(), (mc == SPIDER).float(),
                             fwd, right, g(dist) / 16, vf, vr, g(self.mhp) / 20, timer, (g(self.mhurt_t) > HURT_OPEN).float()], dim=2) * present.unsqueeze(2)
        # nearest arrow coming at us
        adx, adz = self.ax - self.px.unsqueeze(1), self.az - self.pz.unsqueeze(1)
        ad = torch.sqrt(adx * adx + adz * adz + 1e-9)
        closing = (adx * self.avx + adz * self.avz) < 0
        akey = ad + (~(self.aon & closing)) * 1e4
        ai = akey.argmin(1)
        ahas = (self.gather(akey, ai) < 1e3).float()
        ax_, az_ = self.gather(adx, ai), self.gather(adz, ai)
        sp = torch.sqrt(self.gather(self.avx, ai) ** 2 + self.gather(self.avz, ai) ** 2 + 1e-9)
        arrow_f = [ahas, (ax_ * fx + az_ * fz) / 16 * ahas, (-ax_ * fz + az_ * fx) / 16 * ahas, torch.clamp(self.gather(ad, ai) / sp / 20, max=1) * ahas]

        # room to move: distance to the arena edge forward/back/left/right (the mod measures free ground)
        def room(ux, uz):
            tx = torch.where(ux > 1e-6, (ARENA - self.px) / ux, torch.where(ux < -1e-6, (-ARENA - self.px) / ux, torch.full_like(ux, 99.0)))
            tz = torch.where(uz > 1e-6, (ARENA - self.pz) / uz, torch.where(uz < -1e-6, (-ARENA - self.pz) / uz, torch.full_like(uz, 99.0)))
            return torch.clamp(torch.minimum(tx, tz) / 8, max=1)

        room_f = [room(fx, fz), room(-fx, -fz), room(fz, -fx), room(-fz, fx)]
        rest = torch.stack(arrow_f + room_f + [self.malive.sum(1).float() / MAX_MOBS], dim=1)
        return torch.cat([torch.stack(self_f, dim=1), mob_f.reshape(B, -1), rest], dim=1)

    def returns(self):
        """
        Episode score: staying alive first, then killing, then health kept, then speed. Damage dealt counts too
        (progress toward a kill): without it the policy learned to stay away from skeletons for good — chasing one
        costs arrows before it pays — and every fight with an archer ran out the clock.
        """
        won = ~self.malive.any(1) & self.alive
        died = ~self.alive
        dealt = self.dmg_dealt / self.mob_hp_total.clamp(min=1)
        return (self.kills / self.n_mobs.clamp(min=1) + 0.4 * dealt + 0.5 * won - 3.0 * died - 0.05 * self.dmg_taken
                - 0.6 * self.t / T_MAX)


# ---------------------------------------------------------------------------------------------------------------
# Policies
# ---------------------------------------------------------------------------------------------------------------
class RulePolicy:
    """The mod's hand-written fight (CombatBehavior.fight + the creeper back-off of DefenseReflex), for comparison."""

    def act(self, sim: Sim):
        B, dev = sim.B, sim.dev
        dx, dz, dist = sim.mob_dist()
        d = sim.gather(dist, sim.target)
        p = torch.clamp((sim.atk_t + 0.5) / SWORD_T, 0, 1)
        ready = p >= 0.95
        melee = sim.malive & ((sim.mclass == ZOMBIE) | (sim.mclass == SPIDER) | (sim.mclass == CREEPER))
        closest = torch.where(melee, dist, torch.full_like(dist, 1e4)).min(1).values
        move = torch.zeros(B, dtype=torch.long, device=dev)
        in_reach = d < REACH + 0.3
        # strike when ready (jump crits without a shield: on the ground jump, swing on the way down)
        strike = ready | (d > REACH + 1)
        crit_ok = ~sim.has_shield
        jump = strike & in_reach & crit_ok & (sim.py <= 0)
        attack = strike & in_reach & (~crit_ok | ((sim.py > 0) & (sim.vy < 0)))
        approach = strike & ~in_reach
        move = torch.where(approach, torch.full_like(move, 1), move)
        sprint = approach & (d < 12)
        # recharging: shield at whoever is about to hit, step back inside 2.5, close in past reach
        recharging = ~strike
        guard = recharging & sim.has_shield & (closest < 3.2) & (p < 0.85)
        shield = guard
        move = torch.where(recharging & (d < 2.5), torch.full_like(move, 2), move)
        move = torch.where(recharging & ~guard & (d > REACH - 0.3), torch.full_like(move, 1), move)
        # a lit creeper within 5: back away from it (the reflex outranks everything)
        lit = (sim.mtype == CREEPER) & sim.malive & (sim.mswell > 0) & (dist < 5)
        any_lit = lit.any(1)
        move = torch.where(any_lit, torch.full_like(move, 2), move)
        attack = attack & ~any_lit
        shield = shield | (any_lit & sim.has_shield)
        return move, sprint, jump, attack, shield


def param_shapes():
    return [("w1", (OBS, HIDDEN)), ("b1", (HIDDEN,)), ("w2", (HIDDEN, HIDDEN)), ("b2", (HIDDEN,)), ("w3", (HIDDEN, OUTS)), ("b3", (OUTS,))]


def n_params():
    return sum(math.prod(s) for _, s in param_shapes())


def unflatten(theta):
    """theta [P, D] -> dict of [P, ...] views."""
    out, i = {}, 0
    for name, shape in param_shapes():
        n = math.prod(shape)
        out[name] = theta[:, i:i + n].view(theta.shape[0], *shape)
        i += n
    return out


def init_theta(device, seed=0):
    g = torch.Generator().manual_seed(seed)
    parts = []
    for name, shape in param_shapes():
        if name.startswith("w"):
            parts.append((torch.randn(*shape, generator=g) / math.sqrt(shape[0])).flatten())
        else:
            parts.append(torch.zeros(math.prod(shape)))
    return torch.cat(parts).to(device)


def mlp_act(params, obs, P, E):
    """Batched forward pass: env b uses population member b // E."""
    x = obs.view(P, E, OBS)
    h = torch.tanh(torch.baddbmm(params["b1"].unsqueeze(1), x, params["w1"]))
    h = torch.tanh(torch.baddbmm(params["b2"].unsqueeze(1), h, params["w2"]))
    o = torch.baddbmm(params["b3"].unsqueeze(1), h, params["w3"]).view(P * E, OUTS)
    move = o[:, :MOVES].argmax(1)
    return move, o[:, MOVES] > 0, o[:, MOVES + 1] > 0, o[:, MOVES + 2] > 0, o[:, MOVES + 3] > 0


class Runner:
    """
    A Sim of fixed size with its policy. On CUDA one tick (policy + physics) is captured once as a graph and then
    replayed, so the per-kernel launch cost (which dominated: ~350 small kernels a tick) is paid once. `act(sim)`
    must only read tensors that stay put: the sim's buffers, a parameter buffer refilled in place.
    """

    def __init__(self, n: int, device: torch.device, act):
        self.sim = Sim(n, device)
        self.act = act
        self.graph = None

    def _tick(self):
        self.sim.step(*self.act(self.sim))

    def run(self, scen: Scenarios, seed: int = 0) -> Sim:
        sim = self.sim
        if sim.dev.type == "cuda" and self.graph is None:
            sim.load(scen, seed)
            side = torch.cuda.Stream()
            side.wait_stream(torch.cuda.current_stream())
            with torch.cuda.stream(side):  # warm-up outside the capture (lazy inits, allocator)
                for _ in range(3):
                    self._tick()
            torch.cuda.current_stream().wait_stream(side)
            self.graph = torch.cuda.CUDAGraph()
            with torch.cuda.graph(self.graph):
                self._tick()
        sim.load(scen, seed)
        for tick in range(T_MAX):
            # checking costs a GPU->CPU sync: only now and then
            if tick % 50 == 0 and bool(sim.done.all()):
                break
            if self.graph is not None:
                self.graph.replay()
            else:
                self._tick()
        return sim


def rollout(scen: Scenarios, device, act, seed=0) -> Sim:
    """One batch with a one-off runner (the graph capture is paid each call: fine for evaluations)."""
    return Runner(len(scen), device, act).run(scen, seed)


def summary(sim: Sim):
    won = (~sim.malive.any(1) & sim.alive).float().mean().item()
    died = (~sim.alive).float().mean().item()
    return dict(score=round(sim.returns().mean().item(), 4), won=round(won, 4), died=round(died, 4), dmg=round(sim.dmg_taken.mean().item(), 2),
                kills=round((sim.kills / sim.n_mobs.clamp(min=1)).mean().item(), 4), secs=round((sim.t.mean() / 20).item(), 2))


# ---------------------------------------------------------------------------------------------------------------
# Replays for the viewer (tools/combatsim/viewer.html)
# ---------------------------------------------------------------------------------------------------------------
VIZ_DIR = os.path.join(OUT, "viz")
VIZ_FIGHTS = [
    ("Three zombies", [(ZOMBIE, 8, 1), (ZOMBIE, 9, -2), (ZOMBIE, 7, 3)], 0.0, 5.0, True),
    ("Zombie, archer behind", [(ZOMBIE, 6, 0), (SKELETON, -10, 4)], 8.0, 6.0, True),
    ("Creeper among zombies", [(CREEPER, 7, 2), (ZOMBIE, 8, -1), (ZOMBIE, -7, 0)], 2.0, 5.0, False),
    ("Surrounded", [(SPIDER, 6, -3), (SKELETON, 10, 6), (ZOMBIE, -6, -6), (ZOMBIE, -5, 7)], 15.0, 6.0, True),
    ("Hoglin, crossbow behind", [(HOGLIN, 6, 1), (XBOW_PIGLIN, -9, 5)], 15.0, 6.0, True),
    ("Piglin pack", [(PIGLIN, 6, 2), (PIGLIN, 7, -3), (XBOW_PIGLIN, -8, 4), (HOGLIN, -5, -7)], 15.0, 6.0, True),
]
VIZ_EVERY = 2  # ticks per recorded frame
VIZ_MAX_TICKS = 900
VIZ_KEEP = 7  # recorded generations kept (plus the first one and the rules)


def viz_scenarios() -> Scenarios:
    """The same four fights for every recorded generation, so the viewer compares like with like."""
    n = len(VIZ_FIGHTS)
    mtype = torch.zeros(n, MAX_MOBS, dtype=torch.long)
    mx, mz = torch.zeros(n, MAX_MOBS), torch.zeros(n, MAX_MOBS)
    armor, wdmg, shield = torch.zeros(n), torch.zeros(n), torch.zeros(n, dtype=torch.bool)
    for i, (_, mobs, a, w, s) in enumerate(VIZ_FIGHTS):
        for j, (t, x, z) in enumerate(mobs):
            mtype[i, j], mx[i, j], mz[i, j] = t, x, z
        armor[i], wdmg[i], shield[i] = a, w, s
    return Scenarios(mtype, mx, mz, torch.full((n,), 20.0), armor, torch.zeros(n), wdmg, shield, torch.ones(n), torch.zeros(n, dtype=torch.bool))


def record_fights(act, device) -> list:
    """Play the viewer's fights with `act` and keep every VIZ_EVERY-th tick (compact lists, 2 decimals)."""
    scen = viz_scenarios()
    n = len(scen)
    sim = Sim(n, device).load(scen, seed=3)
    frames = [[] for _ in range(n)]
    ended = [False] * n
    r = lambda v: round(float(v), 2)

    def snap(attack):
        cols = [sim.px, sim.pz, sim.py, sim.yaw, sim.hp, sim.shield_t, attack, sim.done]
        px, pz, py, yaw, hp, sh, att, done = (c.tolist() for c in cols)
        mt, ma, mx, mz, mhp, sw, dr, hu = (c.tolist() for c in (sim.mtype, sim.malive, sim.mx, sim.mz, sim.mhp, sim.mswell, sim.mdraw, sim.mhurt_t))
        aon, ax, az = sim.aon.tolist(), sim.ax.tolist(), sim.az.tolist()
        for i in range(n):
            if ended[i]:
                continue
            flags = (1 if sh[i] >= SHIELD_DELAY else 0) | (2 if sh[i] > 0 else 0) | (4 if att[i] else 0)
            mobs = [[mt[i][j], r(mx[i][j]), r(mz[i][j]), r(mhp[i][j]), r(sw[i][j] / FUSE if mt[i][j] == CREEPER else (1 if dr[i][j] > 0 else 0)), 1 if hu[i][j] > HURT_OPEN else 0]
                    for j in range(MAX_MOBS) if ma[i][j]]
            arrows = [[r(ax[i][k]), r(az[i][k])] for k in range(MAX_ARROWS) if aon[i][k]]
            frames[i].append([r(px[i]), r(pz[i]), r(py[i]), r(yaw[i]), round(float(hp[i]), 1), flags, mobs, arrows])
            if done[i]:
                ended[i] = True

    for tick in range(VIZ_MAX_TICKS):
        acts = act(sim)
        if tick % VIZ_EVERY == 0:
            snap(acts[3])
        sim.step(*acts)
        if bool(sim.done.all()):
            snap(torch.zeros_like(sim.done))
            break
    won = (~sim.malive.any(1) & sim.alive).tolist()
    died = (~sim.alive).tolist()
    return [{"frames": frames[i], "won": won[i], "died": died[i], "dmg": round(float(sim.dmg_taken[i]), 1), "secs": round(float(sim.t[i]) / 20, 1)}
            for i in range(n)]


def write_replays(runs: list):
    """runs: [{label, gen, val, fights}], written as replays.js (a <script> the viewer can load from disk) + .json."""
    os.makedirs(VIZ_DIR, exist_ok=True)
    doc = {"fights": [f[0] for f in VIZ_FIGHTS], "every": VIZ_EVERY, "arena": ARENA, "types": TYPE_NAMES, "runs": runs,
           "updated": time.strftime("%H:%M:%S")}
    with open(os.path.join(VIZ_DIR, "replays.json"), "w") as f:
        json.dump(doc, f)
    with open(os.path.join(VIZ_DIR, "replays.js"), "w") as f:
        f.write("window.REPLAYS = ")
        json.dump(doc, f)
        f.write(";\n")


def load_replay_runs() -> list:
    """Recorded runs from an earlier start, if they played the same fights as now."""
    path = os.path.join(VIZ_DIR, "replays.json")
    if os.path.exists(path):
        with open(path) as f:
            doc = json.load(f)
        if doc.get("fights") == [f[0] for f in VIZ_FIGHTS]:
            return doc["runs"]
    return []


def add_replay(runs: list, run: dict) -> list:
    """Keep the rules, the first generation recorded and the last VIZ_KEEP generations."""
    runs = [r for r in runs if r["gen"] != run["gen"]] + [run]
    rules = [r for r in runs if r["gen"] < 0]
    gens = sorted((r for r in runs if r["gen"] >= 0), key=lambda r: r["gen"])
    kept = gens[:1] + [g for g in gens[1:]][-VIZ_KEEP:] if gens else []
    return rules + kept

# ---------------------------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------------------------
def pick_device(name):
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    return torch.device(name)


def cmd_bench(args):
    dev = pick_device(args.device)
    for n in (8192, 65536, 262144):
        scen = sample_scenarios(n, 1)
        runner = Runner(n, dev, RulePolicy().act)
        runner.run(scen)  # first run includes the capture
        t0 = time.time()
        sim = runner.run(scen, seed=1)
        if dev.type == "cuda":
            torch.cuda.synchronize()
        dt = time.time() - t0
        ticks = sim.t.sum().item()
        print(f"{dev} n={n}: {dt:.2f}s, {n / dt:.0f} fights/s, {ticks / dt / 1e6:.2f}M fight-ticks/s  rules: {summary(sim)}", flush=True)


def save_policy(theta, path_json, meta):
    p = unflatten(theta.unsqueeze(0).cpu())
    layers = []
    for w, b in (("w1", "b1"), ("w2", "b2"), ("w3", "b3")):
        # rows = output neurons: Java does out[j] = b[j] + sum_k w[j][k] * in[k]
        layers.append({"w": p[w][0].t().tolist(), "b": p[b][0].tolist()})
    doc = {"version": 1, "obs": OBS, "hidden": HIDDEN, "outs": OUTS, "moves": MOVE_DIRS, "activation": "tanh",
           "layers": layers, **meta}
    os.makedirs(os.path.dirname(path_json), exist_ok=True)
    with open(path_json, "w") as f:
        json.dump(doc, f)


def mlp_logits(params, x):
    """One parameter set (dict of unbatched tensors) on a batch of observations [N, OBS] -> [N, OUTS]."""
    h = torch.tanh(x @ params["w1"] + params["b1"])
    h = torch.tanh(h @ params["w2"] + params["b2"])
    return h @ params["w3"] + params["b3"]


def cmd_bc(args):
    """
    Behaviour cloning: train the policy net to do what the hand-written rules do, as the starting point for evolution.
    From random weights the search learned to survive but never to close on an archer (a local optimum: chasing a
    skeleton costs arrows before it pays); the rules kill skeletons 95% of the time.
    """
    dev = pick_device(args.device)
    xs, moves, bins = [], [], []
    rules = RulePolicy()
    for chunk in range(args.chunks):
        scen = sample_scenarios(args.batch, 70_000 + chunk)
        sim = Sim(len(scen), dev).load(scen, seed=chunk)
        for tick in range(400):
            if tick % 25 == 0 and bool(sim.done.all()):
                break
            acts = rules.act(sim)
            if tick % 3 == 0:
                keep = ~sim.done
                xs.append(sim.observe()[keep].cpu())
                moves.append(acts[0][keep].cpu())
                bins.append(torch.stack([a[keep] for a in acts[1:]], dim=1).float().cpu())
            sim.step(*acts)
        print(f"chunk {chunk}: {sum(len(x) for x in xs)} samples", flush=True)
    x, mv, bn = torch.cat(xs).to(dev), torch.cat(moves).to(dev), torch.cat(bins).to(dev)
    theta = init_theta(dev).clone().requires_grad_()
    opt = torch.optim.Adam([theta], lr=3e-3)
    for epoch in range(args.epochs):
        perm = torch.randperm(len(x), device=dev)
        total, n = 0.0, 0
        for i in range(0, len(x), 8192):
            idx = perm[i:i + 8192]
            params = {k: v[0] for k, v in unflatten(theta.unsqueeze(0)).items()}
            o = mlp_logits(params, x[idx])
            loss = torch.nn.functional.cross_entropy(o[:, :MOVES], mv[idx]) + torch.nn.functional.binary_cross_entropy_with_logits(o[:, MOVES:], bn[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
            n += len(idx)
        print(f"epoch {epoch}: loss {total / n:.4f}", flush=True)
    theta = theta.detach()
    torch.save({"theta": theta}, os.path.join(OUT, "bc.pt"))
    val = sample_scenarios(8192, 12345)
    one = unflatten(theta.unsqueeze(0))
    print("rules ", summary(rollout(val, dev, RulePolicy().act, seed=7)))
    print("cloned", summary(rollout(val, dev, lambda s: mlp_act(one, s.observe(), 1, s.B), seed=7)))
    print("wrote", os.path.join(OUT, "bc.pt"), "- train with --init bc to start from it")

def cmd_train(args):
    dev = pick_device(args.device)
    os.makedirs(OUT, exist_ok=True)
    P, E = args.pop, args.episodes
    D = n_params()
    ckpt = os.path.join(OUT, "policy.pt")
    if args.resume and os.path.exists(ckpt):
        st = torch.load(ckpt, map_location=dev)
        theta, m, v, gen0 = st["theta"], st["m"], st["v"], st["gen"] + 1
        print(f"resumed at generation {gen0}")
    else:
        theta, m, v, gen0 = init_theta(dev), torch.zeros(D, device=dev), torch.zeros(D, device=dev), 0
        if args.init == "bc":
            theta = torch.load(os.path.join(OUT, "bc.pt"), map_location=dev)["theta"]
            print("starting from the behaviour-cloned rules (out/bc.pt)")
    val = sample_mix(args.val, 12345, args.mix)
    base = summary(rollout(val, dev, RulePolicy().act, seed=7))
    print(f"params {D}, population {P} x {E} fights = {P * E} per generation on {dev}")
    print(f"hand-written rules on the validation set: {base}", flush=True)
    # replays for viewer.html: the rules once, then the policy every --viz-every generations
    runs = load_replay_runs() if args.resume else []
    if not any(r["gen"] < 0 for r in runs):
        runs = add_replay(runs, {"label": "hand-written rules", "gen": -1, "val": base, "fights": record_fights(RulePolicy().act, dev)})
        write_replays(runs)
    # parameter buffers the captured graphs read: refilled in place every generation
    pop_buf = torch.zeros(P, D, device=dev)
    one_buf = torch.zeros(1, D, device=dev)
    pop_runner = Runner(P * E, dev, lambda s: mlp_act(unflatten(pop_buf), s.observe(), P, E))
    val_runner = Runner(len(val), dev, lambda s: mlp_act(unflatten(one_buf), s.observe(), 1, len(val)))
    log = open(os.path.join(OUT, "train_log.csv"), "a")
    best_path = os.path.join(OUT, "policy_best.pt")
    best = torch.load(best_path, map_location=dev) if args.resume and os.path.exists(best_path) else None
    best_score = best["res"]["score"] if best is not None and best.get("mix", 0.0) == args.mix else -1e9  # same fights only
    beta1, beta2, eps_adam = 0.9, 0.999, 1e-8
    for gen in range(gen0, gen0 + args.gens):
        t0 = time.time()
        half = torch.randn(P // 2, D, device=dev)
        noise = torch.cat([half, -half])
        pop_buf.copy_(theta.unsqueeze(0) + args.sigma * noise)
        scen = sample_mix(E, 1000 + gen, args.mix).repeat(P)  # every member meets the same fights
        sim = pop_runner.run(scen, seed=gen)
        fit = sim.returns().view(P, E).mean(1)
        ranks = fit.argsort().argsort().float()
        shaped = ranks / (P - 1) - 0.5
        grad = (shaped @ noise) / (P * args.sigma) - args.wd * theta
        m = beta1 * m + (1 - beta1) * grad
        v = beta2 * v + (1 - beta2) * grad * grad
        t = gen + 1
        theta = theta + args.lr * (m / (1 - beta1 ** t)) / (torch.sqrt(v / (1 - beta2 ** t)) + eps_adam)
        dt = time.time() - t0
        line = f"gen {gen} fit mean {fit.mean().item():.3f} max {fit.max().item():.3f} ({dt:.1f}s)"
        if gen % args.eval_every == 0:
            one_buf.copy_(theta.unsqueeze(0))
            res = summary(val_runner.run(val, seed=7))
            line += f" | policy {res} | rules score {base['score']:.3f} died {base['died']:.3f}"
            log.write(f"{gen},{res['score']:.4f},{res['won']:.4f},{res['died']:.4f},{res['dmg']:.3f},{res['kills']:.4f},{res['secs']:.2f}\n")
            log.flush()
            torch.save({"theta": theta, "m": m, "v": v, "gen": gen}, ckpt)
            if gen % args.viz_every == 0:
                snap = theta.clone()
                os.makedirs(os.path.join(OUT, "snapshots"), exist_ok=True)
                torch.save({"theta": snap, "gen": gen, "res": res}, os.path.join(OUT, "snapshots", f"gen_{gen:05d}.pt"))
                one = unflatten(snap.unsqueeze(0))
                fights = record_fights(lambda s: mlp_act(one, s.observe(), 1, s.B), dev)
                runs = add_replay(runs, {"label": f"generation {gen}", "gen": gen, "val": res, "fights": fights})
                write_replays(runs)
            if res["score"] > best_score:
                best_score = res["score"]
                torch.save({"theta": theta, "gen": gen, "res": res, "mix": args.mix}, best_path)
                meta = {"generation": gen, "validation": res, "rules": base}
                save_policy(theta, os.path.join(OUT, "combat_policy.json"), meta)
                if res["score"] > base["score"]:
                    save_policy(theta, os.path.join(ASSETS, "combat_policy.json"), meta)
                    line += "  -> exported (beats the rules)"
        print(line, flush=True)


def threat_features(scen: Scenarios):
    """Features.of in the mod, at the start of each fight (player at the origin)."""
    d = torch.sqrt(scen.mx ** 2 + scen.mz ** 2)
    alive = scen.mtype > 0
    danger_t = torch.tensor(DANGER)[scen.mtype]
    dist_w = torch.where(d < 8, 1.0, 0.5)
    n = alive.sum(1).float()
    x = torch.zeros(len(scen), 12)
    x[:, 0] = scen.hp / 20
    x[:, 1] = scen.armor / 20
    x[:, 2] = torch.clamp(scen.wdmg / 10, max=1)
    x[:, 3] = scen.shield.float()
    x[:, 4] = scen.food
    x[:, 5] = torch.clamp(n / 6, max=1)
    x[:, 6] = torch.clamp(torch.where(alive, d, torch.full_like(d, 1e4)).min(1).values / 16, max=1)
    x[:, 7] = torch.clamp((danger_t * dist_w * alive).sum(1) / 10, max=1.5)
    cls = torch.tensor(MOB_CLASS)[scen.mtype]
    x[:, 8] = ((cls == CREEPER) & (d < 6)).any(1).float()
    x[:, 9] = torch.clamp(((cls == SKELETON) & alive).sum(1).float() / 4, max=1)
    x[:, 10] = 0.0  # ghasts and blazes: not simulated (teacher samples cover them)
    x[:, 11] = scen.dark.float()
    return x


def teacher(x):
    """Features.teacher in the mod: kept for what the sim doesn't model (flying shooters)."""
    strength = x[:, 0] * 1.0 + x[:, 1] * 0.9 + x[:, 2] * 1.2 + x[:, 3] * 0.25 + x[:, 4] * 0.1
    threat = x[:, 7] * 1.8 + x[:, 5] * 0.6 + x[:, 8] * 0.5 + x[:, 9] * 0.4 + x[:, 10] * 0.8 + x[:, 11] * 0.1 - x[:, 6] * 0.2
    return torch.sigmoid(4 * (strength - threat))


def load_policy_act(dev):
    path = os.path.join(OUT, "policy_best.pt")
    if os.path.exists(path):
        theta = torch.load(path, map_location=dev)["theta"]
        one = unflatten(theta.unsqueeze(0))
        return (lambda s: mlp_act(one, s.observe(), 1, s.B)), "trained policy"
    return RulePolicy().act, "hand-written rules"


def cmd_threatnet(args):
    dev = pick_device(args.device)
    act, who = (RulePolicy().act, "hand-written rules") if args.rules else load_policy_act(dev)
    xs, ys = [], []
    runner = Runner(args.batch, dev, act)
    for chunk in range(args.chunks):
        scen = sample_scenarios(args.batch, 50_000 + chunk, max_mobs=6)
        sim = runner.run(scen, seed=chunk)
        went_well = (sim.alive & (sim.start_hp - sim.hp < sim.start_hp * 0.5)).float().cpu()
        xs.append(threat_features(scen))
        ys.append(went_well)
        print(f"chunk {chunk}: {who}, {went_well.mean().item():.3f} went well", flush=True)
    x, y = torch.cat(xs), torch.cat(ys)
    # flying shooters: the sim has none; teacher-labelled samples keep the net cautious about them
    n_fly = len(x) // 10
    xf = x[torch.randint(len(x), (n_fly,))].clone()
    xf[:, 10] = 1.0
    x = torch.cat([x, xf])
    y = torch.cat([y, teacher(xf)])
    w1 = (torch.randn(12, 12) / math.sqrt(12)).requires_grad_()
    b1 = torch.zeros(12).requires_grad_()
    w2 = (torch.randn(12) / math.sqrt(12)).requires_grad_()
    b2 = torch.zeros(()).requires_grad_()
    opt = torch.optim.Adam([w1, b1, w2, b2], lr=0.01)
    for epoch in range(args.epochs):
        perm = torch.randperm(len(x))
        total = 0.0
        for i in range(0, len(x), 4096):
            idx = perm[i:i + 4096]
            z = torch.tanh(x[idx] @ w1.t() + b1) @ w2 + b2
            loss = torch.nn.functional.binary_cross_entropy_with_logits(z, y[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        print(f"epoch {epoch}: loss {total / len(x):.4f}", flush=True)
    with torch.no_grad():
        pred = torch.sigmoid(torch.tanh(x @ w1.t() + b1) @ w2 + b2)
        acc = ((pred > 0.5) == (y > 0.5)).float().mean().item()
    print(f"accuracy {acc:.3f} on {len(x)} fights ({who})")
    doc = {"w1": w1.detach().tolist(), "b1": b1.detach().tolist(), "w2": w2.detach().tolist(), "b2": float(b2), "fights": 0,
           "source": "sim", "trainedOn": int(len(x)), "policy": who}
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "threatnet_sim.json"), "w") as f:
        json.dump(doc, f)
    print("wrote", os.path.join(ASSETS, "threatnet_sim.json"))


def cmd_viz(args):
    """Rebuild the viewer's replays from the saved snapshots: the rules, the first and the latest generations."""
    dev = pick_device(args.device)
    runs = add_replay([], {"label": "hand-written rules", "gen": -1, "val": None, "fights": record_fights(RulePolicy().act, dev)})
    snap_dir = os.path.join(OUT, "snapshots")
    snaps = sorted(f for f in os.listdir(snap_dir) if f.endswith(".pt")) if os.path.isdir(snap_dir) else []
    for name in snaps[:1] + snaps[1:][-VIZ_KEEP:]:
        st = torch.load(os.path.join(snap_dir, name), map_location=dev)
        one = unflatten(st["theta"].unsqueeze(0))
        runs = add_replay(runs, {"label": f"generation {st['gen']}", "gen": st["gen"], "val": st.get("res"),
                                 "fights": record_fights(lambda s: mlp_act(one, s.observe(), 1, s.B), dev)})
        print("recorded generation", st["gen"], flush=True)
    write_replays(runs)
    print("wrote", os.path.join(VIZ_DIR, "replays.js"), "- open tools/combatsim/viewer.html")


def cmd_eval(args):
    dev = pick_device(args.device)
    val = sample_scenarios(args.val, 12345)
    print("rules  ", summary(rollout(val, dev, RulePolicy().act, seed=7)))
    act, who = load_policy_act(dev)
    print(f"{who:7}", summary(rollout(val, dev, act, seed=7)))
    # by situation: how each does when a given kind of mob is in the group
    for kind in (ZOMBIE, SKELETON, CREEPER, SPIDER):
        sub = val.select((val.mtype == kind).any(1).nonzero().squeeze(1))
        r = summary(rollout(sub, dev, RulePolicy().act, seed=7))
        q = summary(rollout(sub, dev, act, seed=7))
        print(f"  with a {TYPE_NAMES[kind]:8}: rules died {r['died']:.3f} dmg {r['dmg']:.1f} | {who} died {q['died']:.3f} dmg {q['dmg']:.1f}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", default="auto")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("bench")
    tr = sub.add_parser("train")
    tr.add_argument("--gens", type=int, default=3000)
    tr.add_argument("--pop", type=int, default=256)
    tr.add_argument("--episodes", type=int, default=64)
    tr.add_argument("--sigma", type=float, default=0.05)
    tr.add_argument("--lr", type=float, default=0.02)
    tr.add_argument("--wd", type=float, default=0.005)
    tr.add_argument("--val", type=int, default=8192)
    tr.add_argument("--eval-every", type=int, default=10)
    tr.add_argument("--viz-every", type=int, default=20, help="record replays for viewer.html every N generations (a multiple of --eval-every)")
    tr.add_argument("--resume", action="store_true")
    tr.add_argument("--init", choices=["random", "bc"], default="random", help="bc: start from the behaviour-cloned rules (run `bc` first)")
    tr.add_argument("--mix", type=float, default=0.0, help="share of hard fights: crowds, Nether mobs, low health, worse gear")
    bc = sub.add_parser("bc")
    bc.add_argument("--batch", type=int, default=8192)
    bc.add_argument("--chunks", type=int, default=8)
    bc.add_argument("--epochs", type=int, default=6)
    th = sub.add_parser("threatnet")
    th.add_argument("--batch", type=int, default=32768)
    th.add_argument("--chunks", type=int, default=16)
    th.add_argument("--epochs", type=int, default=8)
    th.add_argument("--rules", action="store_true", help="label with the hand-written rules, not the trained policy")
    ev = sub.add_parser("eval")
    ev.add_argument("--val", type=int, default=16384)
    sub.add_parser("viz")
    args = ap.parse_args()
    {"bench": cmd_bench, "train": cmd_train, "threatnet": cmd_threatnet, "eval": cmd_eval, "viz": cmd_viz, "bc": cmd_bc}[args.cmd](args)


if __name__ == "__main__":
    main()
