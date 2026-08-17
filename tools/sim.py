#!/usr/bin/env python3
"""
AI 同士だけを回すヘッドレスシミュレータ。

【注意】v2.0 で得点制をやめ、勝ち残り・トーナメント・みんなで勝負の3ルールに
組み替えたため、このスクリプトは現在の実装と合っていない。
数値を取り直すときは Rules.kt / Games.kt に合わせて書き直すこと。


Engine.kt / Games.kt / Game.kt のロジックをそのまま写している。
片方を変えたら必ずもう片方も合わせること。

  python3 tools/sim.py                        じゃんけん1000セッション
  python3 tools/sim.py 5000                   回数指定
  python3 tools/sim.py 5000 daruma            ゲーム指定
  python3 tools/sim.py 5000 janken chizuru shingo   参加者を指定
"""

import json
import math
import os
import random
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

ROCK, SCISSORS, PAPER = 0, 1, 2
STAKES = [1, 1, 2, 1, 3]


def beats(a, b):
    if a == b:
        return 0
    win = (a == ROCK and b == SCISSORS) or \
          (a == SCISSORS and b == PAPER) or \
          (a == PAPER and b == ROCK)
    return 1 if win else -1


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def logit(p):
    q = clamp(p, 0.001, 0.999)
    return math.log(q / (1.0 - q))


def sigmoid(z):
    return 1.0 / (1.0 + math.exp(-z))


def claim_belief(o, size, honesty):
    """相手の予告と一致率から、その相手が実際に取る選択肢の分布を作る"""
    if o.declared < 0:
        return [1.0 / size] * size
    rest = (1.0 - honesty) / (size - 1)
    p = [rest] * size
    p[o.declared] = honesty
    return p


# ---------------------------------------------------------------- GameDef
# Games.kt の各実装に対応する


class Janken:
    max_ai = 5
    id = "janken"
    name = "予告じゃんけん"
    claims = ["グー", "チョキ", "パー"]
    scale = 12.0

    def evaluate(self, actors, me, st, belief):
        score = [0.0] * 3
        for o in actors:
            if o is me or o.declared < 0:
                continue
            p = claim_belief(o, 3, belief(o))
            for h in range(3):
                for i in range(3):
                    score[h] += p[i] * beats(h, i)
        return score

    def resolve(self, actors, st):
        for a in actors:
            g = sum(beats(a.actual, o.actual) * st for o in actors if o is not a)
            if a.declared == a.actual:
                g += st
            a.score += g


class Daruma:
    id = "daruma"
    name = "予告だるまさんがころんだ"
    claims = ["0歩", "1歩", "2歩", "3歩"]
    scale = 15.0

    def payoff(self, mine, others, watched):
        """見張られていて最多を出し、それが単独か全員一致なら捕まる"""
        allv = list(others) + [mine]
        mx = max(allv)
        cnt = sum(1 for v in allv if v == mx)
        if watched and mine == mx and (cnt == 1 or cnt == len(allv)):
            return -1
        return mine

    def evaluate(self, actors, me, st, belief):
        n = len(self.claims)
        others = [o for o in actors if o is not me and o.declared >= 0]
        score = [0.0] * n
        if not others:
            return score
        probs = [claim_belief(o, n, belief(o)) for o in others]

        watched = self.watched_ids(actors)
        me_watched = id(me) in watched

        for k in range(n ** len(others)):
            x, w, combo = k, 1.0, []
            for i in range(len(others)):
                c = x % n
                x //= n
                combo.append(c)
                w *= probs[i][c]
            if w <= 0.0:
                continue
            for h in range(n):
                score[h] += w * self.payoff(h, combo, me_watched)
        return score

    max_ai = 4

    def watched_ids(self, actors):
        """最も多く進むと予告した者が見張られる。同数なら全員が見張られる。"""
        mx = max(a.declared for a in actors if a.declared >= 0)
        return set(id(a) for a in actors if a.declared == mx)

    def resolve(self, actors, st):
        watched = self.watched_ids(actors)
        mx = max(a.actual for a in actors)
        cnt = sum(1 for a in actors if a.actual == mx)
        for a in actors:
            caught = (id(a) in watched and a.actual == mx
                      and (cnt == 1 or cnt == len(actors)))
            g = -1 if caught else a.actual
            if a.declared == a.actual and not caught:
                g += 1
            a.score += g


GAMES = {"janken": Janken(), "daruma": Daruma()}


# ---------------------------------------------------------------- テーブル


def load_characters():
    with open(os.path.join(ASSETS, "characters.json"), encoding="utf-8") as f:
        root = json.load(f)

    chars = {}
    for o in root["characters"]:
        chars[o["id"]] = {
            "id": o["id"],
            "name": o["name"],
            "archetype": o["archetype"],
            "base": o["base_lie_rate"],
            "w": o["situation_weights"],
            "honest": o["presentation"]["honest"],
            "lie": o["presentation"]["lie"],
        }

    for o in root.get("mobs", []):
        b = chars[o["base"]]
        s = o["intensity_scale"]
        u = (1.0 - s) / 3.0
        chars[o["id"]] = {
            "id": o["id"],
            "name": o["name"],
            "archetype": b["archetype"] + "_MOB",
            "base": b["base"],
            "w": {k: v * s for k, v in b["w"].items()},
            "honest": {k: u + s * v for k, v in b["honest"].items()},
            "lie": {k: u + s * v for k, v in b["lie"].items()},
        }

    return chars, root["roster"]["starter"]


CENTER = 0.5


def lie_rate(c, sit):
    # 状況変数は 0.5 を中心に振る。こうしないと重みが平均そのものをずらし、
    # base_lie_rate が「平均の嘘率」として機能しなくなる。
    z = logit(c["base"])
    for k in ("stakes", "elimination_risk", "score_gap", "endgame"):
        z += c["w"].get(k, 0.0) * (sit[k] - CENTER)
    return sigmoid(z)


def sample_intensity(c, lying, rnd):
    p = c["lie"] if lying else c["honest"]
    x = rnd.random()
    if x < p["low"]:
        return "low"
    if x < p["low"] + p["mid"]:
        return "mid"
    return "high"


class Actor:
    def __init__(self, c, prior_n=0, prior_m=0):
        self.c = c
        self.prior_n = prior_n
        self.prior_m = prior_m
        self.score = 0
        self.declared = -1
        self.actual = -1
        self.lying = False
        self.intensity = "mid"
        self.obs = 0
        self.match = 0


class Stat:
    def __init__(self):
        self.rounds = 0
        self.lies = 0
        self.high = 0
        self.high_lies = 0
        self.low = 0
        self.low_lies = 0
        self.score = 0
        self.wins = 0
        self.sessions = 0
        self.by_stakes = defaultdict(lambda: [0, 0])


def situation(game, actors, me, rnd_idx):
    top = max(a.score for a in actors)
    rank = sum(1 for a in actors if a.score > me.score)
    endgame = rnd_idx / float(len(STAKES) - 1)
    return {
        "stakes": STAKES[rnd_idx] / 3.0,
        "elimination_risk": clamp(
            (rank / float(len(actors) - 1)) * endgame, 0.0, 1.0),
        "score_gap": clamp((top - me.score) / game.scale, 0.0, 1.0),
        "endgame": endgame,
    }


def belief_honesty(a):
    """今セッションの観測に、過去の記録を上限つきで混ぜる（Game.kt と同じ）"""
    n = a.obs + a.prior_n
    if n == 0:
        return 0.5
    return clamp((a.match + a.prior_m) / float(n), 0.15, 0.85)


def choose_claim(game, actors, me, st, rnd):
    n = len(game.claims)
    known = sum(1 for o in actors if o is not me and o.declared >= 0)
    if known == 0:
        return rnd.randrange(n)
    if rnd.random() < 0.15:
        return rnd.randrange(n)
    score = game.evaluate(actors, me, st, belief_honesty)
    return max(range(n), key=lambda h: score[h])


def declare(a, hand, sit, rnd):
    """予告する。ここで嘘かどうかが確定する。実際の行動はまだ決めない。"""
    a.lying = rnd.random() < lie_rate(a.c, sit)
    a.declared = hand
    a.intensity = sample_intensity(a.c, a.lying, rnd)


def commit(game, actors, a, st, rnd):
    """最終予告が出そろってから実際の行動を決める。

    正直なら予告通り（＝読まれる代償を負う）。
    嘘なら予告以外から、場の最終予告に対して最も強い選択肢を選ぶ。
    """
    n = len(game.claims)
    if not a.lying:
        a.actual = a.declared
        return
    cand = [h for h in range(n) if h != a.declared]
    if rnd.random() < 0.15:
        a.actual = rnd.choice(cand)
        return
    score = game.evaluate(actors, a, st, belief_honesty)
    a.actual = max(cand, key=lambda h: score[h])


def run_session(game, chars, ids, stats, rnd, priors=None):
    if priors is None:
        actors = [Actor(chars[i]) for i in ids]
    else:
        actors = []
        for i in ids:
            pn, pm = priors.get(i, (0, 0))
            actors.append(Actor(chars[i], min(pn, 20), min(pm, 20)))

    for r, st in enumerate(STAKES):
        for a in actors:
            a.declared = -1

        # 初回予告（同時）
        chosen = {a: choose_claim(game, actors, a, st, rnd) for a in actors}
        for a in actors:
            declare(a, chosen[a], situation(game, actors, a, r), rnd)

        # 最終予告（同時）
        chosen = {a: choose_claim(game, actors, a, st, rnd) for a in actors}
        for a in actors:
            declare(a, chosen[a], situation(game, actors, a, r), rnd)

        # 実行：最終予告が出そろってから決める
        for a in actors:
            commit(game, actors, a, st, rnd)

        for a in actors:
            s = stats[a.c["id"]]
            s.rounds += 1
            s.by_stakes[st][0] += 1
            if a.lying:
                s.lies += 1
                s.by_stakes[st][1] += 1
            if a.intensity == "high":
                s.high += 1
                if a.lying:
                    s.high_lies += 1
            if a.intensity == "low":
                s.low += 1
                if a.lying:
                    s.low_lies += 1

        game.resolve(actors, st)

        for a in actors:
            a.obs += 1
            if a.declared == a.actual:
                a.match += 1

    best = max(a.score for a in actors)
    for a in actors:
        s = stats[a.c["id"]]
        s.score += a.score
        s.sessions += 1
        if a.score == best:
            s.wins += 1

    if priors is not None:
        for a in actors:
            pn, pm = priors.get(a.c["id"], (0, 0))
            priors[a.c["id"]] = (pn + a.obs, pm + a.match)


def pct(n, d):
    if d == 0:
        return "  -  "
    return "%5.1f" % (100.0 * n / d)


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
    chars, starter = load_characters()

    rest = sys.argv[2:]
    game = GAMES["janken"]
    if rest and rest[0] in GAMES:
        game = GAMES[rest[0]]
        rest = rest[1:]
    ids = rest if rest else list(starter)

    for i in ids:
        if i not in chars:
            print("unknown character: " + i)
            return

    rnd = random.Random(20260812)
    stats = {i: Stat() for i in ids}
    # 長期記録を引き継ぐかどうか。LONGTERM=0 で毎回まっさらに戻す。
    priors = {} if os.environ.get("LONGTERM", "1") != "0" else None
    for _ in range(n):
        run_session(game, chars, ids, stats, rnd, priors)

    print("%s / セッション数 %d / 参加 %s" % (game.name, n, ", ".join(ids)))
    print("")
    print("%-8s %-12s %6s %6s %6s %6s %6s" %
          ("id", "archetype", "嘘率", "強気時", "弱気時", "勝率", "平均点"))
    print("-" * 60)
    for i in ids:
        s = stats[i]
        c = chars[i]
        print("%-8s %-12s %s %s %s %s %6.2f" % (
            i, c["archetype"],
            pct(s.lies, s.rounds),
            pct(s.high_lies, s.high),
            pct(s.low_lies, s.low),
            pct(s.wins, s.sessions),
            s.score / float(s.sessions),
        ))

    print("")
    print("配点別の嘘率")
    for i in ids:
        s = stats[i]
        cells = "".join(
            " " + pct(s.by_stakes[k][1], s.by_stakes[k][0])
            for k in sorted(set(STAKES))
        )
        print("%-8s%s" % (i, cells))

    print("")
    print("読み取りの指針")
    print("  強気時と弱気時の差が 30pt 未満だと、人間は数セッションでは気づかない")
    print("  勝率が 20% を切るタイプは、そのままだと選ぶ理由がない")


if __name__ == "__main__":
    main()
