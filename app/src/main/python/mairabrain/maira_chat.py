# Maira ka apna chat brain (zero se trained GPT, ~70M params). Sirf NumPy chahiye.
import json, os, sys, unicodedata
import numpy as np

D = os.path.dirname(os.path.abspath(__file__))

# ---------- tokenizer (ByteLevel BPE, pure python) ----------
def _b2u():
    bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256)); cs = bs[:]; n = 0
    for b in range(256):
        if b not in bs: bs.append(b); cs.append(256 + n); n += 1
    return {b: chr(c) for b, c in zip(bs, cs)}
B2U = _b2u(); U2B = {v: k for k, v in B2U.items()}
_tk = json.load(open(os.path.join(D, "maira_chat_tokenizer.json"), encoding="utf-8"))
VOCAB = _tk["vocab"]; ID2T = {i: t for t, i in VOCAB.items()}
RANK = {tuple(m.split(" ")): i for i, m in enumerate(_tk["merges"])}
SPECIAL = _tk["special"]; ID2SP = {i: t for t, i in SPECIAL.items()}
CFG = _tk["cfg"]

def _cls(c):
    if c.isspace(): return "s"
    if c.isalpha(): return "L"
    if unicodedata.category(c)[0] == "N": return "N"
    return "o"

def pretok(s):  # GPT-2 regex ki nakal, bina 'regex' module
    out = []; i = 0; n = len(s)
    while i < n:
        c = s[i]
        if c == "'":
            for suf in ("re", "ve", "ll", "s", "t", "m", "d"):
                if s.startswith(suf, i + 1): out.append("'" + suf); i += 1 + len(suf); break
            else: suf = None
            if suf: continue
        j = i
        if c == " " and i + 1 < n and _cls(s[i + 1]) != "s": j = i + 1
        k = _cls(s[j])
        if k != "s":
            e = j + 1
            while e < n and _cls(s[e]) == k: e += 1
            out.append(s[i:e]); i = e; continue
        e = i
        while e < n and s[e].isspace(): e += 1
        if e < n and e - i > 1: e -= 1
        out.append(s[i:e]); i = e
    return out

_cache = {}
def bpe(w):
    if w in _cache: return _cache[w]
    p = list(w)
    while len(p) > 1:
        best = min(((RANK.get((p[i], p[i + 1]), 1e18), i) for i in range(len(p) - 1)))
        if best[0] == 1e18: break
        a, b = p[best[1]], p[best[1] + 1]; q = []; i = 0
        while i < len(p):
            if i < len(p) - 1 and p[i] == a and p[i + 1] == b: q.append(a + b); i += 2
            else: q.append(p[i]); i += 1
        p = q
    r = [VOCAB[t] for t in p]; _cache[w] = r; return r

def encode(s):
    s = unicodedata.normalize("NFC", s); ids = []; i = 0; buf = ""
    sp = sorted(SPECIAL, key=len, reverse=True)
    while i < len(s):
        m = next((t for t in sp if s.startswith(t, i)), None)
        if m:
            for w in pretok(buf): ids += bpe("".join(B2U[b] for b in w.encode("utf-8")))
            ids.append(SPECIAL[m]); buf = ""; i += len(m)
        else: buf += s[i]; i += 1
    for w in pretok(buf): ids += bpe("".join(B2U[b] for b in w.encode("utf-8")))
    return ids

def decode(ids):
    t = "".join(ID2T.get(i, "") for i in ids if i not in ID2SP)
    return bytes(U2B[c] for c in t if c in U2B).decode("utf-8", "replace")

# ---------- model ----------
_z = np.load(os.path.join(D, "maira_chat_weights.npz"))
d, L, H, CTX = CFG["d"], CFG["L"], CFG["H"], CFG["ctx"]; hd = d // H
W = {}; Q = {}  # Q: block matrices int8 (RAM bachane ke liye), y = (x @ q) * s
for k in _z.files:
    if k.endswith(".s"): continue
    if k.endswith(".q"):
        n = k[:-2]
        if n.startswith("blocks."): Q[n] = (np.ascontiguousarray(_z[k].T), _z[n + ".s"].reshape(1, -1).astype(np.float32))
        else: W[n] = _z[k].astype(np.float32) * _z[n + ".s"]
    else: W[k] = _z[k]
def mm(x, n): q, s = Q[n + ".weight"]; return (x @ q) * s + W[n + ".bias"]
def ln(x, p): m = x.mean(-1, keepdims=True); v = x.var(-1, keepdims=True); return (x - m) / np.sqrt(v + 1e-5) * W[p + ".weight"] + W[p + ".bias"]
def gelu(x): return 0.5 * x * (1 + np.tanh(0.7978845608 * (x + 0.044715 * x ** 3)))

def forward(ids, cache, start):
    x = W["tok.weight"][ids] + W["pos.weight"][start:start + len(ids)]; T = len(ids)
    for i in range(L):
        b = f"blocks.{i}."
        qkv = mm(ln(x, b + "ln1"), b + "qkv")
        q, k, v = [a.reshape(T, H, hd).transpose(1, 0, 2) for a in np.split(qkv, 3, -1)]
        if cache[i] is not None: k = np.concatenate([cache[i][0], k], 1); v = np.concatenate([cache[i][1], v], 1)
        cache[i] = (k, v); S = k.shape[1]
        a = q @ k.transpose(0, 2, 1) / np.sqrt(hd)
        mask = np.triu(np.ones((T, S), bool), S - T + 1); a[:, mask] = -1e9
        a = np.exp(a - a.max(-1, keepdims=True)); a /= a.sum(-1, keepdims=True)
        y = (a @ v).transpose(1, 0, 2).reshape(T, d)
        x = x + mm(y, b + "proj")
        x = x + mm(gelu(mm(ln(x, b + "ln2"), b + "fc")), b + "fc2")
    return W["tok.weight"] @ ln(x[-1], "lnf")

U, M, E = SPECIAL["<|user|>"], SPECIAL["<|maira|>"], SPECIAL["<|end|>"]
EOT = SPECIAL["<|endoftext|>"]

def chat(q, n=120, temp=0.7, topk=40, rep=1.15, seed=None):
    rng = np.random.default_rng(seed)
    ids = [U] + encode(q.strip()) + [M]; ids = ids[-(CTX - n):]
    cache = [None] * L; lg = forward(ids, cache, 0); pos = len(ids); out = []
    for _ in range(n):
        lg = lg.copy()
        for t in set(out): lg[t] = lg[t] / rep if lg[t] > 0 else lg[t] * rep
        lg /= temp; top = np.argpartition(lg, -topk)[-topk:]
        p = np.exp(lg[top] - lg[top].max()); p /= p.sum()
        nx = int(top[rng.choice(len(top), p=p)])
        if nx in (E, EOT, U) or pos >= CTX: break
        out.append(nx); lg = forward([nx], cache, pos); pos += 1
    return decode(out).strip()

if __name__ == "__main__":
    if len(sys.argv) > 1: print(chat(" ".join(sys.argv[1:]))); sys.exit()
    print("Maira chat (zero se trained). Sawal likho, exit = band")
    while True:
        try: t = input("tum> ")
        except EOFError: break
        if t.strip().lower() in ("exit", "quit"): break
        print("Maira>", chat(t))
