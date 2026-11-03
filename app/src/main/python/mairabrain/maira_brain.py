#!/usr/bin/env python3
# Maira Brain v5 - Termux tester (sirf numpy chahiye: pkg install python-numpy)
# Use: python maira_brain.py            (interactive)
#      python maira_brain.py "kal subah 7 baje ka alarm laga do"
import json, re, sys, os, datetime, difflib
import numpy as np
from urllib.parse import quote
D = os.path.dirname(os.path.abspath(__file__))
meta = json.load(open(os.path.join(D, "nlu_meta.json"), encoding="utf-8"))
Wt = np.load(os.path.join(D, "maira_brain_weights.npz"))
C2 = {c: i for i, c in enumerate(meta["chars"])}
WAKE = re.compile(r"^(hey maira|ok maira|maira),?\s*", re.I)
APPS = {"whatsapp":"WhatsApp","youtube":"YouTube","instagram":"Instagram","chrome":"Chrome","camera":"Camera","gallery":"Gallery","settings":"Settings","calculator":"Calculator","spotify":"Spotify","facebook":"Facebook","telegram":"Telegram","maps":"Maps","gmail":"Gmail","play store":"Play Store","termux":"Termux","clock":"Clock","files":"Files","chatgpt":"ChatGPT","snapchat":"Snapchat","calendar":"Calendar","contacts":"Contacts","notes":"Notes","jio cinema":"JioCinema","hotstar":"Hotstar","netflix":"Netflix"}

ALIAS = {"insta":"instagram","yt":"youtube","fb":"facebook","whats app":"whatsapp","wa":"whatsapp","playstore":"play store","gpt":"chatgpt","map":"maps","camra":"camera","calculater":"calculator","setting":"settings","photos":"gallery","google maps":"maps","google chrome":"chrome","vhatsep":"whatsapp","watsapp":"whatsapp","whatsap":"whatsapp"}
REL = {"bhavala": "bhau", "bhaula": "bhau", "aaila": "aai", "aaichi": "aai", "babanna": "baba", "babala": "baba", "taila": "tai", "dadala": "dada", "tai la": "tai", "aai la": "aai", "kakala": "kaka", "mamala": "mama", "aajila": "aaji", "aajobanna": "aajoba"}
TIMEQ = re.compile(r"time|tym|baj|samay|samey|waqt|vakt|ghadi|clock|tarikh|tareekh|date|din|vaar|day|month|mahina|saal|year|kitne|aaj|today|vel|kiti")
def find_app(x):
    xl = x.lower(); ks = sorted(list(APPS) + list(ALIAS), key=len, reverse=True)
    for k in ks:
        if re.search(r"(?<![a-z])" + re.escape(k) + r"(?![a-z])", xl): k = ALIAS.get(k, k); return APPS[k]
    for w in re.findall(r"[a-z]{4,}", xl):
        g = difflib.get_close_matches(w, list(APPS), 1, 0.8)
        if g: return APPS[g[0]]
    return None
def sig(x): return 1 / (1 + np.exp(-x))
def gru(xs, p):  # PyTorch GRU cell semantics
    Wih, Whh, bih, bhh = Wt[p+"w_ih"], Wt[p+"w_hh"], Wt[p+"b_ih"], Wt[p+"b_hh"]
    H = Whh.shape[1]; h = np.zeros(H, np.float32); out = []
    for x in xs:
        gi = Wih @ x + bih; gh = Whh @ h + bhh
        r = sig(gi[:H] + gh[:H]); z = sig(gi[H:2*H] + gh[H:2*H]); n = np.tanh(gi[2*H:] + r * gh[2*H:])
        h = (1 - z) * n + z * h; out.append(h)
    return np.stack(out)
def encode(ids):
    x = Wt["emb"][ids]
    for l in range(2):
        f = gru(x, f"l{l}f_"); b = gru(x[::-1], f"l{l}b_")[::-1]; x = np.concatenate([f, b], -1)
    return x
def gelu(x): return 0.5 * x * (1 + np.tanh(0.7978845608 * (x + 0.044715 * x ** 3)))

from maira_norm import pre, norm_time, norm_date, WD
WD_KEYS = sorted(WD, key=len, reverse=True)
def resolve(v, now):  # markers -> real date/time (app yahi karegi)
    if v.startswith("@w"):  # agla woh din (aaj ho to agle hafte)
        d = (int(v[2:]) - now.weekday()) % 7 or 7; return (now + datetime.timedelta(days=d)).strftime("%Y-%m-%d")
    if v.startswith("@d+"): return (now + datetime.timedelta(days=int(v[3:]))).strftime("%Y-%m-%d")
    m = re.match(r"@t\+(\d+)([mh])", v)
    if m: return (now + datetime.timedelta(minutes=int(m.group(1)) * (60 if m.group(2) == "h" else 1))).strftime("%H:%M")
    if v.endswith("?"):  # ambiguous 12h -> next upcoming
        h, mi = map(int, v[:-1].split(":")); c = [now.replace(hour=h % 12, minute=mi, second=0), now.replace(hour=h % 12 + 12, minute=mi, second=0)]
        nxt = [t for t in c if t > now]; return (nxt[0] if nxt else c[0]).strftime("%H:%M")
    return v

PENDING = None  # ask_user ke baad agla jawab yahan bharta hai
SMSRX = re.compile(r"\b(msg|message|mesej|sms|text|whatsapp|watsapp)\b")
def ask(q, sp, pend):
    global PENDING; PENDING = pend
    return {"actions": [{"tool": "ask_user", "parameters": {"question": q}}], "confirmationRequired": False, "reply": q, "speech": sp}
def sms_contact(x):
    m = re.search(r"(?:to|ko|la|ke liye)\s+(\w+)$", x) or re.search(r"(\w+)\s+(?:ko|la)\b", x)
    v = m.group(1) if m else ""
    return "" if v.lower() in ("mujhe", "mala", "me", "message", "msg", "whatsapp", "sms", "text") else v.capitalize()
PLAT = [("WhatsApp", r"whats\s?app|watsapp|whatsap|vhatsapp|wp"), ("Instagram", r"instagram|insta|ig"), ("Facebook", r"facebook|fb|messenger")]
def plat_split(t):  # "whatsapp pe papa ko bol hi" -> ("WhatsApp", "papa ko message bhejo hi")
    tl = pre(t).lower()
    for name, rx in PLAT:
        m = re.search(rf"\b(?:{rx})\b\s*(?:pe|par|pr|me|mein|mai|var|madhe|madhye|se|ke through|on|dwara)?\b", tl)
        if not m: continue
        rest = (tl[:m.start()] + " " + tl[m.end():]).strip()
        if not (re.search(r"\b(msg|message|mesej|sms|text|dm|bol|bolo|bol do|keh|kaho|bata|batao|sang|likh|bhej|pathav|reply)\w*", rest) or re.fullmatch(rf"(?:{rx})\s*(?:karo|kar|kar do|kardo)", tl) or re.search(rf"\b(\w+) (?:ko|la) (?:{rx}) (?:karo|kar|kar do|kardo)\b", tl) or re.search(rf"(?:{rx}) (?:karo|kar)\s+\w+ (?:ko|la)\b", tl)): return None, t
        if re.search(r"\b(photo|pic|video|file|image|pdf|location|document|sticker|status)\b", rest): return "AGENT", t
        if re.search(r"padh|padho|sunao|kya aaya|kya aya|kisne|aaye|aaya|\bread\b|dikhao|check", rest): return None, t
        rest = re.sub(r"^(on|pe|par)\s+", "", rest)
        rest = re.sub(r"^(?:karo|kar|msg karo|message karo|msg bhejo|message bhejo|msg kar|text karo)\s+(\w+)\s+(?:ko|la)\b(.*)", r"\1 ko message bhejo\2", rest)
        rest = re.sub(r"\b(ko|la)\s+(karo|kar|kar do|kardo)\b", r"\1 message karo", rest)
        rest = re.sub(r"\b(ko|la)\s+(bol do|bolo|bol|keh do|kaho|batao|sang)\b", r"\1 message bhejo", rest)
        return name, re.sub(r"\s+", " ", rest)
    return None, t
def to_plat(r, name):
    out = []
    for a in r["actions"]:
        pm = a["parameters"]
        if a["tool"] == "send_sms":
            cn, msg = pm.get("contact_name", ""), pm.get("message", "")
            if name == "WhatsApp":
                out += [{"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "open"}},
                        {"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "click", "text": "Search"}},
                        {"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "type_text", "text": cn}},
                        {"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "click", "text": cn}},
                        {"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "type_text", "text": msg}},
                        {"tool": "screen_action", "parameters": {"app_name": "WhatsApp", "action": "click", "text": "Send"}}]
            else:
                out.append({"tool": "agent_task", "parameters": {"goal": f"{name} pe {cn} ko DM/message bhejo: {msg}"}})
            r["reply"] = f"{name} pe {cn} ko bhej doon: {msg}? Haan bolo to bhejti hoon."; r["speech"] = f"{name} पर {cn} को भेज दूं?"; r["confirmationRequired"] = True
        elif a["tool"] == "ask_user":
            r["reply"] = r["reply"].replace(" ko kya message", f" ko {name} pe kya message").replace("Kisko message", f"{name} pe kisko message")
            pm["question"] = r["reply"]; out.append(a)
        else: out.append(a)
    r["actions"] = out; return r

YTRX = r"\b(you\s?tube|yt|youtub|utube)\b"
def R_(acts, reply, speech=None, conf=False): return {"actions": acts, "confirmationRequired": conf, "reply": reply, "speech": speech or reply}
def yt_rule(text):
    x = pre(text).lower().strip(" .!?")
    x = re.sub(r"^(maira|hey maira|ok maira|bhai|yaar|zara|please|sun|arey|jara|ek kaam karo)\s+", "", x)
    yt = re.search(YTRX, x); ytm = re.search(r"\b(you\s?tube|yt) music\b", x)
    vid = re.search(r"\b(video|videos|vdo|vidio|shorts?|reel|channel)\b", x)
    if not (yt or vid): return None
    # current video par kaam
    if re.search(r"\b(subscribe|subscrib|sabskraib)\w*", x): return R_([{"tool": "agent_task", "parameters": {"goal": text.strip()}}], "Ruko, subscribe kar rahi hoon.", "रुको, सब्सक्राइब कर रही हूँ।")
    if re.search(r"\b(like|dislike|share|save|watch later)\b", x) and re.search(r"\b(ye|is|isko|iss|yeh|this|current|video|karo|kar|kar do)\b", x) and not re.search(r"\b(dhoondh|search|chalao|dikhao)\b", x):
        return R_([{"tool": "agent_task", "parameters": {"goal": "YouTube pe jo video chal raha hai: " + text.strip()}}], "Ruko, kar rahi hoon.", "रुको, कर रही हूँ।")
    if re.search(r"\bcomment\b", x) and re.search(r"\b(karo|kar|likho|likh do|daalo|post)\b", x):
        c = re.sub(r".*?comment\s*(karo|kar do|kar|likho|likh do|daalo|post karo)?\s*(ki|that)?\s*", "", x, 1).strip()
        if not c: return ask("Kya comment likhun?", "क्या कमेंट लिखूँ?", None)
        return R_([{"tool": "agent_task", "parameters": {"goal": f"YouTube pe jo video chal raha hai us par ye comment post karo: {c}"}}], f"Ye comment daal doon: {c}? Haan bolo to post karti hoon.", "ये कमेंट डाल दूँ?", True)
    if re.search(r"\b(skip|aage|forward|peeche|rewind|back)\b", x) and re.search(r"\d+\s*(sec|second|minute|min)", x):
        return R_([{"tool": "agent_task", "parameters": {"goal": "YouTube video: " + text.strip()}}], "Ruko, kar rahi hoon.", "रुको, कर रही हूँ।")
    if re.fullmatch(r"(?:(?:ye|is|yeh)\s+)?(?:video|youtube|yt)?\s*(?:ko\s+)?(pause|ruko|ruk|roko|rok do|stop|thamb)\s*(karo|kar|kar do|do)?", x): return R_([{"tool": "media_control", "parameters": {"action": "pause"}}], "Rok diya.", "रोक दिया।")
    if re.fullmatch(r"(?:(?:ye|is|yeh)\s+)?(?:video)\s*(?:ko\s+)?(play|resume|chalu|chala|phir se chala)\s*(karo|kar|kar do|do|de)?", x): return R_([{"tool": "media_control", "parameters": {"action": "play"}}], "Chala diya.", "चला दिया।")
    if re.fullmatch(r"(agla|next|aglaa)\s+(video|short)\s*(chalao|lagao|dikhao|karo|chala)?", x): return R_([{"tool": "media_control", "parameters": {"action": "next"}}], "Agla laga diya.", "अगला लगा दिया।")
    if re.fullmatch(r"(pichla|previous|pehle wala)\s+(video|short)\s*(chalao|lagao|dikhao|karo|chala)?", x): return R_([{"tool": "media_control", "parameters": {"action": "previous"}}], "Pichla laga diya.", "पिछला लगा दिया।")
    if yt and re.search(r"\b(band|close|bandh|hatao|exit)\b", x) and not re.search(r"\b(chalao|dikhao|search)\b", x):
        return R_([{"tool": "screen_action", "parameters": {"app_name": "YouTube", "action": "home"}}], "YouTube band kar diya.", "यूट्यूब बंद कर दिया।")
    for kw, url, nm in ((r"history", "https://www.youtube.com/feed/history", "history"), (r"subscriptions?", "https://www.youtube.com/feed/subscriptions", "subscriptions"), (r"watch later", "https://www.youtube.com/playlist?list=WL", "watch later"), (r"trending", "https://www.youtube.com/feed/trending", "trending")):
        if yt and re.search(rf"\b{kw}\b", x): return R_([{"tool": "open_url", "parameters": {"url": url}}], f"YouTube {nm} khol rahi hoon.", f"यूट्यूब {nm} खोल रही हूँ।")
    q = x
    q = re.sub(r"\b(on|in)\s+(?=(you\s?tube|yt)\b)", " ", q)
    q = re.sub(r"\b(you\s?tube|yt|youtub|utube)\s*(music)?\s*(app)?\s*(pe|par|pr|me|mein|mai|var|madhe|on|se)?\b", " ", q)
    q = re.sub(r"\b(search|sarch|dhoondo|dhoondh|dhundo|khojo|find)\b\s*(karo|kar|kar do|ke|kr)?", " ", q)
    q = re.sub(r"\b(chalao|chala do|chala de|chala|chalu karo|lagao|laga do|laga|dikhao|dikha do|dikha|play karo|play kar|play|sunao|bajao|lav|kholo|khol|open karo|open|dekhna hai|dekhni hai|dekho|dekhao|mujhe|please|na|bhai)\b", " ", q)
    q = re.sub(r"\b(karo|kar|kar do|do|de|hai|ek|koi|kya|naya kya|chal raha)\b", " ", q)
    q = re.sub(r"\b(ka|ki|ke)\s*$", " ", q); q = re.sub(r"\s+", " ", q).strip()
    if re.fullmatch(r"(shorts?|short video|shorts video)", q): return R_([{"tool": "open_url", "parameters": {"url": "https://www.youtube.com/shorts"}}], "YouTube Shorts khol rahi hoon.", "यूट्यूब शॉर्ट्स खोल रही हूँ।")
    if not yt: 
        if re.search(r"\b(ka|ki|ke|wala|wali)\s+(video|videos|vdo)\b", x) and q and not re.fullmatch(r"(video|videos|vdo)", q): pass
        else: return None
    if not q or re.fullmatch(r"(video|videos|vdo|app|channel)", q):
        if ytm: return R_([{"tool": "open_app", "parameters": {"app_name": "YouTube Music"}}], "YouTube Music khol rahi hoon.", "यूट्यूब म्यूज़िक खोल रही हूँ।")
        if yt: return R_([{"tool": "open_app", "parameters": {"app_name": "YouTube"}}], "YouTube khol rahi hoon.", "यूट्यूब खोल रही हूँ।")
        return None
    if ytm: return R_([{"tool": "open_url", "parameters": {"url": "https://music.youtube.com/search?q=" + quote(q)}}], f"YouTube Music pe {q} laga rahi hoon.", f"यूट्यूब म्यूज़िक पर {q} लगा रही हूँ।")
    return R_([{"tool": "open_url", "parameters": {"url": "https://www.youtube.com/results?search_query=" + quote(q)}}], f"YouTube pe {q} dhoondh rahi hoon.", f"यूट्यूब पर {q} ढूँढ रही हूँ।")

LAST_ALARM = None  # (time_marker, day_marker) — "wednesday ko bola tha" jaisa sudhaar
_DAYN = ["Somvar", "Mangalvar", "Budhvar", "Guruvar", "Shukravar", "Shanivar", "Ravivar"]
_DAYK = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
def _has_day(x): return bool(re.search(r"\b(" + "|".join(WD_KEYS) + r"|kal|parso|aaj|udya)\b", x))
def make_alarm(tm, dm, now):
    global LAST_ALARM
    if tm.startswith("@t"): tm = resolve(tm, now); dm = None
    LAST_ALARM = (tm, dm)
    if tm.endswith("?"):
        h, mi = map(int, tm[:-1].split(":"))
        if dm:  # alag din pe subah/shaam pata nahi -> pucho
            dn = _DAYN[datetime.date.fromisoformat(resolve(dm, now)).weekday()]
            hm = f"{h}:{mi:02d}" if mi else f"{h} baje"
            return ask(f"{dn} {hm} subah ya shaam?", f"{dn} {hm} सुबह या शाम?", ("alarm_ap", (tm, dm)))
        else: H = int(resolve(tm, now).split(":")[0])
        tm = f"{H:02d}:{mi:02d}"
    H, mi = map(int, tm.split(":"))
    d = datetime.date.fromisoformat(resolve(dm, now)) if dm else None
    tgt = datetime.datetime.combine(d, datetime.time(H, mi)) if d else None
    h12 = H % 12 or 12; part = "subah" if H < 12 else ("dopahar" if H < 16 else ("shaam" if H < 20 else "raat"))
    when = f"{h12}:{mi:02d} {part}" if mi else f"{h12} baje {part}"
    if not tgt or datetime.timedelta(0) < tgt - now <= datetime.timedelta(hours=24):
        dn = "" if not tgt else ("aaj " if tgt.date() == now.date() else "kal ")
        return R_([{"tool": "set_alarm", "parameters": {"time": tm}}], f"Done! {dn}{when} ka alarm laga diya.", f"हो गया! {dn}{when} का अलार्म लगा दिया।")
    if tgt <= now: return R_([], "Ye time nikal chuka hai, alarm nahi lagaya.")
    wd = tgt.weekday()
    return R_([{"tool": "set_alarm", "parameters": {"time": tm, "day": _DAYK[wd], "date": tgt.strftime("%Y-%m-%d")}}],
              f"Done! {_DAYN[wd]} ({tgt.day} {tgt.strftime('%b')}) {when} ka alarm laga diya. Phone ka alarm har {_DAYN[wd]} bajega, baad mein band karna ho to bolna.",
              f"हो गया! {_DAYN[wd]} {when} का अलार्म लगा दिया।")

def multi_alarm(x, now):
    toks = re.findall(r"\b(\d{1,2})(?:[:.](\d\d))?\b(?!\s*(?:minute|min|ghante|ghanta|second|tarikh|date|%))", x)
    toks = [(int(h), int(m or 0)) for h, m in toks if 0 < int(h) <= 23]
    if len(toks) < 2 or len(toks) > 10: return None
    part = re.search(r"\b(subah|dopahar|shaam|sham|raat|am|pm|morning|evening|night)\b", x)
    dm = norm_date(x) if _has_day(x) else None
    if not part and any(h <= 12 for h, _ in toks) and dm and not re.search(r"\b(uthana|utha|jaga)\b", x):
        return ask(f"{len(toks)} alarm: subah ke ya shaam ke?", f"सुबह के या शाम के?", ("alarm_multi", (toks, dm)))
    return build_multi(toks, dm, part.group(1) if part else ("subah" if re.search(r"\b(uthana|utha|jaga)\b", x) else None), now)
def build_multi(toks, dm, part, now):
    acts, names = [], []
    for h, m in toks:
        if part: tm = norm_time(f"{h}:{m:02d} {part}") or f"{h:02d}:{m:02d}"
        else: tm = f"{h:02d}:{m:02d}" if h > 12 else f"{h:02d}:{m:02d}?"
        r = make_alarm(tm, dm, now)
        if r["actions"] and r["actions"][0]["tool"] == "set_alarm": acts.append(r["actions"][0]); names.append(r["actions"][0]["parameters"]["time"])
    if not acts: return R_([], "Ye time nikal chuka hai, alarm nahi lagaya.")
    return R_(acts, f"Done! {len(acts)} alarm laga diye: " + ", ".join(names) + ".", f"{len(acts)} अलार्म लगा दिए।")

def cancel_alarm_rule(x, now):
    global LAST_ALARM
    al = re.search(r"\b(alarm|alaram)\b", x); off = re.search(r"\b(band|bandh|cancel|hatao|hata do|delete|off|mat bajana|nako|remove|mita do|mitao|mita)\b", x)
    if al and not off and re.search(r"\b(on|chalu|chaalu|shuru|enable|wapas|vapas|fir se|phir se|dobara|start)\b", x) and not re.search(r"\b(naya|new)\b", x):
        if re.search(r"\b(sab|sabhi|saare|sare|all|sagle)\b", x):
            return R_([{"tool": "enable_alarm", "parameters": {"all": "true"}}], "Saare alarm chalu kar rahi hoon.", "सारे अलार्म चालू कर रही हूँ।")
        tm = norm_time(x)
        if tm and not tm.startswith("@"):
            h, mi = int(tm[:2]), int(tm[3:5]); lab = f"{h % 12 or 12}:{mi:02d}" if mi else f"{h % 12 or 12} baje"
            st = make_alarm(tm, None, now)["actions"]
            stt = st[0]["parameters"]["time"] if st else tm.rstrip("?")
            return R_([{"tool": "enable_alarm", "parameters": {"time": tm, "set_time": stt}}], f"{lab} wala alarm chalu kar rahi hoon.", "अलार्म चालू कर रही हूँ।")
        return None
    if not off: return None
    if not al:  # "6:10 ka bhi band karo" — time + band = alarm band
        tm0 = norm_time(x)
        if tm0 and not tm0.startswith("@") and not re.search(r"\b(wifi|bluetooth|torch|flash|gaana|song|music|video|youtube|data|net|hotspot|location|app|screen|phone|volume|awaaz|awaz|tv|light|fan|timer)\b", x):
            h, mi = int(tm0[:2]), int(tm0[3:5]); lab = f"{h % 12 or 12}:{mi:02d}" if mi else f"{h % 12 or 12} baje"
            LAST_ALARM = None
            return R_([{"tool": "cancel_alarm", "parameters": {"time": tm0}}], f"{lab} wala alarm band kar rahi hoon.", "अलार्म बंद कर रही हूँ।")
    if not al:  # "band kar do" turant alarm lagane ke baad
        if LAST_ALARM and len(x.split()) <= 4 and not re.search(r"\b(wifi|bluetooth|torch|flash|gaana|song|music|video|youtube|data|net|hotspot|location|app|screen|phone)\b", x):
            tm, dm = LAST_ALARM; LAST_ALARM = None
            if tm.endswith("?"): tm = resolve(tm, now)
            return R_([{"tool": "cancel_alarm", "parameters": {"time": tm}}], f"Theek hai, {tm} wala alarm band kar diya.", "ठीक है, अलार्म बंद कर दिया।")
        return None
    LAST_ALARM = None
    if al and re.search(r"\b(delete|remove|mita do|mitao|mita|kadhun tak)\b", x):  # delete = Clock list se hatao, sirf off nahi
        if re.search(r"\b(sab|sabhi|saare|sare|all|sagle)\b", x):
            return R_([{"tool": "delete_alarm", "parameters": {"all": "true"}}], "Saare alarm delete kar rahi hoon.", "सारे अलार्म डिलीट कर रही हूँ।", conf=True)
        tm = norm_time(x)
        if tm and not tm.startswith("@"):
            h, mi = int(tm[:2]), int(tm[3:5]); lab = f"{h % 12 or 12}:{mi:02d}" if mi else f"{h % 12 or 12} baje"
            return R_([{"tool": "delete_alarm", "parameters": {"time": tm}}], f"{lab} wala alarm delete kar rahi hoon.", "अलार्म डिलीट कर रही हूँ।")
    if re.search(r"\b(sab|sabhi|saare|sare|all|sagle)\b", x):
        return R_([{"tool": "cancel_alarm", "parameters": {"all": "true"}}], "Saare alarm band kar rahi hoon.", "सारे अलार्म बंद कर रही हूँ।", conf=True)
    tm = norm_time(x)
    if tm and not tm.startswith("@"):
        h, mi = int(tm[:2]), int(tm[3:5]); lab = f"{h % 12 or 12}:{mi:02d}" if mi else f"{h % 12 or 12} baje"
        return R_([{"tool": "cancel_alarm", "parameters": {"time": tm}}], f"{lab} wala alarm band kar rahi hoon.", "अलार्म बंद कर रही हूँ।")
    part = "shaam" if re.search(r"\b(shaam|sham|pm|evening|raat|night|dopahar)\b", x) else ("subah" if re.search(r"\b(subah|savere|am|morning|sakali)\b", x) else "")
    return ask("Kitne baje wala alarm band karun?", "कितने बजे वाला अलार्म बंद करूँ?", ("cancel_al", part))
def _ap(tm, h_hint=None):  # "06:00?" -> subah (4-9 baje uthne wale alarm), warna jaisa hai
    if not tm.endswith("?"): return tm
    h, mi = int(tm[:2]), int(tm[3:5])
    return f"{h:02d}:{mi:02d}" if 4 <= h <= 9 else tm
def modify_alarm_rule(x, now):  # "6 baje ke alarm ko 4 baje set karo"
    m = re.search(r"^(.*?)\b(?:wale|wala|vale|ke|ka|ki)\s+(?:alarm|alaram)\s+(?:ko|la)\s+(.+?)\s*(?:baje)?\s*(?:pe|par|ko)?\s*(?:set|kar|karo|kar do|badlo|badal do|change|shift|rakho|lagao|move)\b", x)
    if not m: return None
    a, b = norm_time(m.group(1)), norm_time(m.group(2) if re.search(r"baje|:|\d\s*(am|pm)|subah|shaam|sham|raat", m.group(2)) else m.group(2) + " baje")
    if not a or not b or a.startswith("@") or b.startswith("@"): return None
    old_t = _ap(a); new_t = b
    if new_t.endswith("?"):
        pm = (not old_t.endswith("?")) and int(old_t[:2]) >= 12
        h, mi = int(new_t[:2]), int(new_t[3:5])
        new_t = f"{(h % 12) + (12 if pm else 0):02d}:{mi:02d}" if not old_t.endswith("?") else _ap(new_t)
    r = make_alarm(new_t, None, now)
    if not r["actions"] or r["actions"][0]["tool"] != "set_alarm": return r
    nt = r["actions"][0]["parameters"]["time"]
    return R_([{"tool": "cancel_alarm", "parameters": {"time": old_t}}, r["actions"][0]], f"{old_t.rstrip('?')} wala alarm band karke {nt} ka laga rahi hoon.", "अलार्म बदल रही हूँ।")
def alarm_rule(text, now):
    x = pre(text).lower()
    x = re.sub(r"\b([01]?\d|2[0-3]) ([0-5]\d)\b(?=\s*(ka|ke|ki|wala|wale|baje|am|pm|alarm|$))", r"\1:\2", x)
    x = re.sub(r"\b(delet|delte|dlt|dilit|dilet|remov)\b", "delete", x)
    _mo = modify_alarm_rule(x, now)
    if _mo: return _mo
    _c = cancel_alarm_rule(x, now)
    if _c: return _c
    if re.search(r"\b(alarm|alaram|uthana|utha dena|jaga dena|jagana)\b", x):
        if re.search(r"\b(band|cancel|hatao|delete|off|roj|roz|rooz|rojana|daily|har din|everyday|weekdays?|weekend)\b", x): return None
        _m = multi_alarm(x, now)
        if _m: return _m
        tm = norm_time(x)
        if not tm: return None
        if tm.endswith("?") and re.search(r"\b(uthana|utha dena|utha|jaga dena|jagana|jaga)\b", x): tm = tm[:-1]  # uthana = subah
        return make_alarm(tm, norm_date(x) if _has_day(x) else None, now)
    global LAST_ALARM
    if LAST_ALARM and len(x.split()) <= 6 and re.search(r"\b(bola tha|bola|boli thi|kaha tha|nahi|galat|matlab|not|instead|change|badal)\b", x):
        tm = norm_time(x); dm = norm_date(x) if _has_day(x) else None
        if not tm and not dm: return None
        r = make_alarm(tm or LAST_ALARM[0], dm or LAST_ALARM[1], now)
        r["reply"] = "Sorry! Theek kar diya. " + r["reply"]; return r
    LAST_ALARM = None
    return None

def run(text, now=None, force=None):
    global PENDING
    if not force and not PENDING:
        _y = yt_rule(text)
        if _y: return _y
        _a = alarm_rule(text, now or datetime.datetime.now())
        if _a: return _a
    if not force and not PENDING:
        name, t2 = plat_split(text)
        if name == "AGENT": return {"actions": [{"tool": "agent_task", "parameters": {"goal": text.strip()}}], "confirmationRequired": False, "reply": "Ruko, kar rahi hoon.", "speech": "रुको, कर रही हूँ।"}
        if name:
            if not sms_contact(t2) or re.fullmatch(r"(msg|message|dm|text)\s*(bhejo|karo|kar|bhej|kar do|send karo)?", t2):
                return ask(f"{name} pe kisko message bhejun?", f"{name} पर किसको मैसेज भेजूँ?", ("sms_to", None, name))
            V = r"(?:bhejo|bhej do|bhej de|bhej|send karo|send kar|send|bol do|bolo|bol|keh do|kaho|likh do|likho|pathav|kar do|karo)"
            m = re.fullmatch(rf"(\w+) (?:ko|la) (?:(?:msg|message|text|sms) (?:bhejo|karo|kar|bhej do|kar do)(?: ki)? )?(.+?)(?: {V})?(?: na| please| plz)?", t2)
            if m and m.group(2) and not re.fullmatch(rf"(msg|message|text|sms|{V[3:-1]})( {V})?", m.group(2)):
                cn = m.group(1).capitalize(); cn = REL.get(cn.lower(), cn.lower()).capitalize(); msg = re.sub(r"^(ki|that|saying)\s+", "", m.group(2))
                return to_plat({"actions": [{"tool": "send_sms", "parameters": {"contact_name": cn, "message": msg}}], "confirmationRequired": True, "reply": "", "speech": ""}, name)
            r = run(t2, now)
            if not (r and r["actions"] and r["actions"][0]["tool"] in ("send_sms", "ask_user")): r = run(pre(text), now, None) if False else r
            if r and r["actions"] and r["actions"][0]["tool"] in ("send_sms", "ask_user"):
                if PENDING and len(PENDING) == 2: PENDING = PENDING + (name,)
                return to_plat(r, name)
    if PENDING and len(PENDING) == 3 and not force:
        k, d, name = PENDING; PENDING = (k, d); r = run(text, now)
        if PENDING and len(PENDING) == 2: PENDING = PENDING + (name,)
        return to_plat(r, name) if r else r
    now = now or datetime.datetime.now()
    if PENDING and not force:
        kind, d = PENDING; PENDING = None; ans = text.strip()
        if re.fullmatch(r"(rehne do|rahne do|cancel|mat bhejo|nahi|nako|chhodo|chodo|kuch nahi|no)\W*", ans.lower()):
            return {"actions": [], "confirmationRequired": False, "reply": "Theek hai, rehne diya.", "speech": "ठीक है, रहने दिया।"}
        if kind == "sms":
            r0 = run(text, now)  # naya command bola ho to wahi karo
            if r0 and r0["actions"] and r0["actions"][0]["tool"] not in ("fallback_llm", "ask_user", "send_sms", "agent_task", "open_app", "play_music", "make_call"): return r0
            PENDING = None
            return {"actions": [{"tool": "send_sms", "parameters": {"contact_name": d, "message": ans}}], "confirmationRequired": True, "reply": f"{d} ko message bhej rahi hoon: {ans}", "speech": f"{d} को मैसेज भेज रही हूँ।"}
        if kind == "alarm_multi":
            toks, dm = d; a = pre(ans).lower()
            pm_ = re.search(r"\b(shaam|sham|pm|evening|raat|night|dopahar|afternoon)\b", a); am_ = re.search(r"\b(subah|savere|am|morning|sakali)\b", a)
            if not (pm_ or am_): return run(text, now)
            return build_multi(toks, dm, "shaam" if pm_ else "subah", now)
        if kind == "cancel_al":
            a = pre(ans).lower(); tm = norm_time(a)
            if not tm or tm.startswith("@"):
                if re.search(r"\b(sab|sabhi|saare|sare|all)\b", a): return cancel_alarm_rule("sab alarm band", now)
                return run(text, now)
            if re.search(r"\b(shaam|sham|pm|evening|raat|night|dopahar)\b", a): d = "shaam"
            elif re.search(r"\b(subah|savere|am|morning|sakali)\b", a): d = "subah"
            if tm.endswith("?") and d:
                h, mi = map(int, tm[:-1].split(":")); h = h % 12 + (12 if d == "shaam" else 0); tm = f"{h:02d}:{mi:02d}"
            return cancel_alarm_rule(f"{tm} ka alarm band", now) if False else R_([{"tool": "cancel_alarm", "parameters": {"time": tm}}], f"{tm.rstrip('?')} wala alarm band kar rahi hoon.", "अलार्म बंद कर रही हूँ।")
        if kind == "alarm_ap":
            tm, dm = d; h, mi = map(int, tm[:-1].split(":")); a = pre(ans).lower()
            if len(a.split()) > 3 or re.search(r"\d", a): return run(text, now)  # naya command
            if re.search(r"\b(shaam|sham|pm|evening|raat|night|dopahar|afternoon)\b", a): h = 12 if h == 12 else h + 12
            elif not re.search(r"\b(subah|savere|am|morning|sakali)\b", a):
                r0 = run(text, now)
                if r0 and r0["actions"] and r0["actions"][0]["tool"] != "fallback_llm": return r0
                h = h % 12
            return make_alarm(f"{h:02d}:{mi:02d}", dm, now)
        if kind == "sms_to": return ask(f"{ans.title()} ko kya message bhejun?", f"{ans.title()} को क्या मैसेज भेजूँ?", ("sms", ans.title()))
        if kind == "call": return {"actions": [{"tool": "make_call", "parameters": {"contact_name": ans.title()}}], "confirmationRequired": True, "reply": f"{ans.title()} ko call laga rahi hoon.", "speech": f"{ans.title()} को कॉल लगा रही हूँ।"}
    x = WAKE.sub("", pre(WAKE.sub("", text.strip()))).strip()
    if not x: return None
    o = encode(np.array([C2.get(ch, 1) for ch in x.lower()]))
    pooled = np.concatenate([o.mean(0), o.max(0)])
    li = Wt["i2_w"] @ gelu(Wt["i1_w"] @ pooled + Wt["i1_b"]) + Wt["i2_b"]
    pr = np.exp(li - li.max()); pr /= pr.sum()
    tmpl = meta["intents"][int(li.argmax())]
    if pr.max() < 0.5: tmpl = "fallback_llm|0||"  # pakka nahi -> Maira ka apna chat brain
    if force: tmpl = force
    need = set(re.findall(r"\{(\w+)\}", tmpl))  # sirf template ke slots allow
    allow = np.array([l == "O" or l[2:] in need for l in meta["labels"]])
    lt = o @ Wt["tag_w"].T + Wt["tag_b"]; lt[:, ~allow] = -1e9
    tags = [meta["labels"][i] for i in lt.argmax(-1)]
    spans = {}; i = 0
    while i < len(tags):
        if tags[i].startswith("B-"):
            k = tags[i][2:]; j = i + 1
            while j < len(tags) and tags[j] == "I-" + k: j += 1
            spans.setdefault(k, x[i:j]); i = j
        else: i += 1
    def val(k):
        if k == "time": return norm_time(x) or ""
        if k == "date": return norm_date(x)
        v = spans.get(k, ""); b = re.sub(r"\d+$", "", k)
        if b in ("number", "level"):
            n = re.findall(r"\d+", v) or re.findall(r"(?:volume|awaaz|level|number|brightness|roshni)\D{0,12}?(\d+)|(\d+)\s*(?:percent|%|number|no\b|dabao|pe click)", x)
            n = [g if isinstance(g, str) else (g[0] or g[1]) for g in n] or re.findall(r"\d+", x); return n[0] if n else v
        if b == "app_name" and v.lower() in ALIAS: v = ALIAS[v.lower()]
        if b == "app_name" and v.lower() not in APPS:  # spelling galti theek
            g = difflib.get_close_matches(v.lower(), list(APPS), 1, 0.75); v = g[0] if g else v
        if b == "app_name" and v.lower() not in APPS: v = find_app(x) or v  # poore vaakya me app dhoondo
        if b == "text" and v.lower() == "search": return "Search"
        if b == "app_name": return APPS.get(v.lower(), v.title())
        if b == "contact_name":
            POS = r"(majhya|majha|majhi|maze|mazya|meri|mere|mera|my|apne|apni)"
            if re.fullmatch(POS, v.lower().strip()):
                m2 = re.search(POS + r"\s+(\w+)", x.lower()); v = m2.group(2) if m2 else v
            v = re.sub("^" + POS + r"\s+", "", v.lower())
            v = REL.get(v, re.sub(r"(la|ko|na)$", "", v) if v not in [r.lower() for r in REL.values()] and len(v) > 4 and v.endswith(("ala", "ila", "nna")) else v)
        if b in ("contact_name", "artist_name"): return " ".join(w.capitalize() for w in v.split())
        return v
    dsl = re.sub(r"\{(\w+)\}", lambda m: val(m.group(1)), tmpl)
    acts_s, conf, reply, speech = dsl.split("|"); acts = []
    for a in [a for a in acts_s.split(";") if a]:
        t, _, ps = a.partition(" "); p = {}
        for kv in [k for k in re.split(r",(?=\w+=)", ps) if k]:
            k, _, v = kv.partition("=")
            if t == "set_alarm" and k == "time" and v.endswith("?") and 3 <= int(v[:2]) <= 8: v = v[:-1]  # alarm 3-8 baje = subah
            if t != "set_alarm" and k in ("time", "start_time") and v.endswith("?") and 1 <= int(v[:2]) <= 7 and not re.search(r"@d\+0", ps): v = f"{int(v[:2])+12:02d}{v[2:-1]}"  # aage ke din meeting 1-7 baje = dopahar/shaam
            if t != "set_alarm" and k in ("time", "start_time") and v.endswith("?") and not re.search(r"@d\+0", ps) and re.search(r"date=@", ps): v = v[:-1] if int(v[:2]) >= 8 else v  # aage ke din 8-11 = subah
            v = resolve(v, now) if k in ("time", "date", "start_time") else v
            if t == "create_reminder" and k == "text" and v:
                i = x.lower().find(v.lower())
                if i >= 0:
                    tail = re.sub(r"\s*\b(yaad dila\w*|yaad karana|remind\w*|reminder|na|please|plz|ye|ki)\s*$", "", x[i:].strip(), flags=re.I)
                    tail = re.sub(r"\s*\b(yaad dila\w*|reminder)\s*$", "", tail, flags=re.I)
                    if 0 < len(tail.split()) <= 10 and not re.search(r"\d+\s*baje", tail): v = tail
            p[k] = int(v) if k in ("level", "number", "count") and v.isdigit() else v
        acts.append({"tool": t, "parameters": p})
    if force in (None, "fallback_llm|0||") and acts and acts[0]["tool"] == "fallback_llm":  # chat me jaane se pehle keyword se command bachao
        xl = x.lower(); on = re.search(r"\b(on|chalu|chaalu|start|jala|jalao|enable|suru)\b", xl); off = re.search(r"\b(off|band|bandh|disable|bujha|bujhao|hata)\b", xl)
        st = "off" if off and (not on or off.start() > on.start()) else ("on" if on else None)
        for kw, tool in (("wifi", "toggle_wifi"), ("bluetooth", "toggle_bluetooth"), ("hotspot", "toggle_hotspot"), ("torch|flash", "toggle_flashlight"), ("location|gps", "toggle_location"), ("mobile data|internet|net\b", "toggle_mobile_data"), ("dnd|do not disturb", "toggle_dnd")):
            if st and re.search(kw, xl): acts = [{"tool": tool, "parameters": {"state": st}}]; reply = speech = "Theek hai, " + ("chalu" if st == "on" else "band") + " kar diya."; break
        else:
            pn = next((n for n, rx in PLAT if re.search(rf"\b(?:{rx})\b", xl)), None)
            if pn and re.search(r"kya aaya|kya aya|kisne|message aaye|msg aaye|padho|padh ke|naye message|notification", xl): acts = [{"tool": "read_notifications", "parameters": {"app_name": pn}}]; reply = "Ruko, dekhti hoon kya aaya hai."; speech = "रुको, देखती हूँ क्या आया है।"
            elif re.fullmatch(r"(ruko|ruk|roko|stop|thamb|thamba|pause|bas)( karo| kar| ja| jao)?", xl): acts = [{"tool": "media_control", "parameters": {"action": "pause"}}]; reply = "Rok diya."; speech = "रोक दिया।"
            elif re.search(r"yaad dila|\bremind", xl) and norm_time(xl):
                tm = norm_time(xl); dm = norm_date(xl)
                if tm.endswith("?") and dm != "@d+0": tm = tm[:-1] if int(tm[:2]) >= 8 else f"{int(tm[:2]) % 12 + 12:02d}{tm[2:-1]}"
                m_ = re.search(r"(?:yaad dila\w*|remind\w*(?: me)?)\s+(?:ki |ke |ke liye |to |about )?(.+)$", x.strip(), re.I)
                txt = m_.group(1).strip() if m_ else "Reminder"
                if re.search(r"\d", txt) or len(txt.split()) > 10: txt = "Reminder"
                acts = [{"tool": "create_reminder", "parameters": {"text": txt, "date": resolve(dm, now), "time": resolve(tm, now)}}]; reply = "Done, yaad dila dungi."; speech = "ठीक है, याद दिला दूँगी।"
            elif re.search(r"\b(meeting|calendar)\b", xl) and norm_time(xl):
                tm = norm_time(xl); dm = norm_date(xl)
                if tm.endswith("?") and dm != "@d+0": tm = tm[:-1] if int(tm[:2]) >= 8 else f"{int(tm[:2]) % 12 + 12:02d}{tm[2:-1]}"
                acts = [{"tool": "create_calendar_event", "parameters": {"title": "Meeting", "date": resolve(dm, now), "start_time": resolve(tm, now)}}]; reply = "Calendar mein daal diya."; speech = "कैलेंडर में डाल दिया।"
            elif re.search(r"\bsilent\b|\bmute\b", xl): acts = [{"tool": "set_volume", "parameters": {"level": 0}}]; reply = "Phone silent kar diya."; speech = "फ़ोन साइलेंट कर दिया।"
            elif re.search(r"\b(gaana|gaane|gana|song|songs|music)\b", xl) and re.search(r"chala|laga|baja|play|suna|lav", xl): acts = [{"tool": "play_music", "parameters": {}}]; reply = "Chalo, laga rahi hoon!"; speech = "चलो, लगा रही हूँ!"
    if not force:
        ts = [a["tool"] for a in acts]; FB = "fallback_llm|0||"
        if any(t in ("read_time", "read_date") for t in ts) and not TIMEQ.search(x): return run(text, now, FB)
        if any(t in ("read_notifications", "read_screen") for t in ts) and not re.search(r"notif|message|msg|screen|likha|padh|read|vach|alert|aaye|aala", x.lower()): return run(text, now, FB)
        if re.search(r"\d+\s*(aur|plus|\+|minus|-|into|guna|x|\*|divide|bhag|/)\s*\d+.*(kitn|kya|=|hota|hote|equal)", x): return run(text, now, FB)
        for a in acts:
            pm = a["parameters"]
            if a["tool"] in ("screen_action", "open_url") and pm.get("action") in ("open", None) and len(acts) > 1 and any(q.get("parameters", {}).get("text") == "" for q in acts):
                ap = pm.get("app_name") or find_app(x)
                if ap: return {"actions": [{"tool": "open_app", "parameters": {"app_name": ap}}], "confirmationRequired": False, "reply": f"{ap} khol rahi hoon.", "speech": f"{ap} खोल रही हूँ।"}
            if a["tool"] == "send_sms" or (a["tool"] in ("agent_task", "fallback_llm", "open_app") and SMSRX.search(x.lower()) and sms_contact(x)):
                cn = pm.get("contact_name") if a["tool"] == "send_sms" else ""
                cn = cn or sms_contact(x); msg = pm.get("message") if a["tool"] == "send_sms" else ""
                if cn and not msg: return ask(f"{cn} ko kya message bhejun?", f"{cn} को क्या मैसेज भेजूँ?", ("sms", cn))
                if not cn: return ask("Kisko message bhejun?", "किसको मैसेज भेजूँ?", ("sms_to", None))
            if a["tool"] == "open_app" and not pm.get("app_name"):
                if SMSRX.search(x.lower()): return ask("Kisko message bhejun?", "किसको मैसेज भेजूँ?", ("sms_to", None))
                return run(text, now, FB)
            if a["tool"] == "agent_task" and len(pm.get("goal", "").split()) < 2: pm["goal"] = text.strip()
            if a["tool"] == "make_call" and not pm.get("contact_name"): return ask("Kisko call karun?", "किसको कॉल करूँ?", ("call", None))
            if a["tool"] == "create_reminder" and not pm.get("text"): return run(text, now, FB)
            for k in [k for k, v in pm.items() if v == "" and k in ("artist_name", "song_name")]: pm.pop(k)
            if a["tool"] == "media_control" and not pm.get("action"):
                for w, act in (("pause|ruk|roko|thamb|band", "pause"), ("next|agla|pudh", "next"), ("previous|pichla|mag", "previous"), ("play|chala|resume|chalu", "play")):
                    if re.search(w, x.lower()): pm["action"] = act; break
    acts, reply, speech = post(acts, reply, speech, now)
    return {"actions": acts, "confirmationRequired": conf == "1", "reply": reply, "speech": speech}

from urllib.parse import quote
DAYS = ["Somvaar", "Mangalvaar", "Budhvaar", "Guruvaar", "Shukravaar", "Shanivaar", "Ravivaar"]
def post(acts, reply, speech, now):  # brain ke internal tools -> app ke tools (maira-system-prompt-v11)
    out = []
    for a in acts:
        t, pr = a["tool"], a["parameters"]
        if t == "navigate": out.append({"tool": "open_url", "parameters": {"url": "https://www.google.com/maps/dir/?api=1&destination=" + quote(pr.get("place", ""))}})
        elif t == "web_search": out.append({"tool": "open_url", "parameters": {"url": "https://www.google.com/search?q=" + quote(pr.get("query", ""))}})
        elif t == "youtube_search": out.append({"tool": "open_url", "parameters": {"url": "https://www.youtube.com/results?search_query=" + quote(pr.get("query", ""))}})
        elif t == "calendar_event": out.append({"tool": "create_calendar_event", "parameters": {"title": pr.get("title", ""), "date": pr.get("date", ""), "start_time": pr.get("time", "")}})
        elif t == "read_time":
            h12 = now.hour % 12 or 12; reply = f"Abhi {h12}:{now.minute:02d} baje hain."; speech = f"अभी {h12} बजकर {now.minute} मिनट हुए हैं।"
        elif t == "read_date":
            reply = f"Aaj {DAYS[now.weekday()]}, {now.day} {now.strftime('%B')} {now.year} hai."; speech = reply
        else: out.append(a)
    return out, reply, speech

if __name__ == "__main__":
    if len(sys.argv) > 1: print(json.dumps(run(" ".join(sys.argv[1:])), ensure_ascii=False, indent=1)); sys.exit()
    print("Maira Brain v5 test. Command likho (exit = band):")
    while True:
        try: t = input("> ")
        except EOFError: break
        if t.strip().lower() in ("exit", "quit"): break
        print(json.dumps(run(t), ensure_ascii=False, indent=1))
