# Maira text normalizer (rules, no model): Devanagari -> Roman, spelling variants, number words, time parsing.
# Used both in training (nlu.py) and inference (maira_brain.py), so model always sees the same kind of text.
import re

# ---------- Devanagari -> Roman ----------
WORDS = {
 "व्हाट्सएप": "whatsapp", "व्हाट्सऐप": "whatsapp", "वॉट्सऐप": "whatsapp", "व्हॉट्सॲप": "whatsapp", "यूट्यूब": "youtube", "युट्यूब": "youtube",
 "इंस्टाग्राम": "instagram", "इन्स्टाग्राम": "instagram", "इंस्टा": "insta", "फेसबुक": "facebook", "कैमरा": "camera", "कॅमेरा": "camera",
 "गैलरी": "gallery", "गॅलरी": "gallery", "सेटिंग्स": "settings", "सेटिंग": "settings", "क्रोम": "chrome", "टेलीग्राम": "telegram",
 "स्पॉटिफाई": "spotify", "जीमेल": "gmail", "मैप्स": "maps", "कैलकुलेटर": "calculator", "टर्मक्स": "termux", "प्ले": "play", "स्टोर": "store",
 "वाईफाई": "wifi", "वायफाय": "wifi", "वाइफाइ": "wifi", "वाई-फाई": "wifi", "ब्लूटूथ": "bluetooth", "ब्लुटूथ": "bluetooth",
 "अलार्म": "alarm", "रिमाइंडर": "reminder", "कॉल": "call", "फोन": "phone", "फ़ोन": "phone", "मैसेज": "message", "मेसेज": "message",
 "गाना": "gaana", "गाने": "gaane", "गाणे": "gaane", "गाणं": "gaana", "म्यूजिक": "music", "वॉल्यूम": "volume", "आवाज़": "awaaz", "आवाज": "awaaz",
 "बैटरी": "battery", "बॅटरी": "battery", "चार्ज": "charge", "नोटिफिकेशन": "notification",
 "करो": "karo", "कर": "kar", "करना": "karna", "कीजिए": "karo", "करा": "kara", "दो": "do", "दे": "de", "दीजिए": "do", "खोलो": "kholo", "खोल": "khol", "उघड": "ughad",
 "चालू": "chalu", "चालु": "chalu", "बंद": "band", "ऑन": "on", "ऑफ": "off", "लगाओ": "lagao", "लगा": "laga", "लाव": "lav", "लावा": "lava", "चलाओ": "chalao",
 "बजाओ": "bajao", "सुनाओ": "sunao", "भेजो": "bhejo", "पाठव": "pathav", "बताओ": "batao", "सांग": "sang", "रोको": "roko", "अगला": "agla", "पुढचं": "pudhcha",
 "को": "ko", "ला": "la", "का": "ka", "की": "ki", "के": "ke", "चा": "cha", "ची": "chi", "चे": "che", "पे": "pe", "पर": "par", "में": "mein", "मे": "mein",
 "आज": "aaj", "कल": "kal", "परसों": "parso", "उद्या": "udya", "आज": "aaj", "सुबह": "subah", "सकाळी": "sakali", "शाम": "shaam", "संध्याकाळी": "sandhyakali",
 "रात": "raat", "रात्री": "ratri", "दोपहर": "dopahar", "दुपारी": "dupari", "बजे": "baje", "वाजता": "vajta", "साढ़े": "saade", "साडे": "saade", "सवा": "sava", "पौने": "paune",
 "मिनट": "minute", "मिनिट": "minute", "मिनिटांनी": "minute baad", "घंटे": "ghante", "तास": "ghante", "बाद": "baad", "नंतर": "nantar",
 "क्या": "kya", "है": "hai", "हैं": "hain", "कैसे": "kaise", "कौन": "kaun", "कितनी": "kitni", "कितना": "kitna", "मुझे": "mujhe", "मला": "mala", "और": "aur", "आणि": "ani",
 "मम्मी": "mummy", "पापा": "papa", "आई": "aai", "बाबा": "baba", "दीदी": "didi", "भैया": "bhaiya", "ताई": "tai", "दादा": "dada", "प्लीज": "please", "याद": "yaad", "दिलाना": "dilana",
}
V = {"अ": "a", "आ": "aa", "इ": "i", "ई": "i", "उ": "u", "ऊ": "u", "ऋ": "ri", "ए": "e", "ऐ": "ai", "ओ": "o", "औ": "au", "ऑ": "o", "ॲ": "e"}
M = {"ा": "a", "ि": "i", "ी": "i", "ु": "u", "ू": "u", "ृ": "ri", "े": "e", "ै": "ai", "ो": "o", "ौ": "au", "ॉ": "o", "ॅ": "e"}
K = {"क": "k", "ख": "kh", "ग": "g", "घ": "gh", "ङ": "n", "च": "ch", "छ": "chh", "ज": "j", "झ": "jh", "ञ": "n", "ट": "t", "ठ": "th", "ड": "d", "ढ": "dh", "ण": "n",
     "त": "t", "थ": "th", "द": "d", "ध": "dh", "न": "n", "प": "p", "फ": "ph", "ब": "b", "भ": "bh", "म": "m", "य": "y", "र": "r", "ल": "l", "ळ": "l", "व": "v",
     "श": "sh", "ष": "sh", "स": "s", "ह": "h", "क़": "k", "ख़": "kh", "ग़": "g", "ज़": "z", "ड़": "d", "ढ़": "dh", "फ़": "f"}
DIG = str.maketrans("०१२३४५६७८९", "0123456789")
def _tr(w):
    if w in WORDS: return WORDS[w]
    out = []; i = 0; n = len(w)
    while i < n:
        ch = w[i]
        if ch in K:
            c = K[ch]; i += 1
            if i < n and w[i] == "़": i += 1
            if i < n and w[i] in M: out.append(c + M[w[i]]); i += 1
            elif i < n and w[i] == "्": out.append(c); i += 1
            else: out.append(c + ("a" if i < n and (w[i] in K or w[i] in "ंँ") else ""))
        elif ch in V: out.append(V[ch]); i += 1
        elif ch in "ंँ": out.append("n"); i += 1
        elif ch == "ः": out.append("h"); i += 1
        else: out.append(ch); i += 1
    return "".join(out)
def deva(s):
    s = s.translate(DIG).replace("।", " ")
    if not re.search(r"[\u0900-\u097F]", s): return s
    return re.sub(r"[\u0900-\u097F]+", lambda m: _tr(m.group()), s)

# ---------- spelling variants ----------
FIX = [(r"\btaim[ae]r\b|\btaymar\b", "timer"), (r"\bskrina?shot\b|\bskreenshot\b|\bscreen shot\b", "screenshot"), (r"\bbrai?ta?nes+\b|\bbrightnes\b", "brightness"),
       (r"\bhota?spot\b|\bhot spot\b", "hotspot"), (r"\bselphi\b|\bselfy\b", "selfie"), (r"\bpho?ta?o\b|\bfoto\b", "photo"), (r"\bphlaish\s?la?ait\b|\bflash ?light\b", "flashlight"),
       (r"\bnotiphikeshan\b", "notification"), (r"\bkailendar\b|\bkaileṃdar\b", "calendar"), (r"\bmiting\b|\bmeeṭing\b", "meeting"), (r"\bvhatsaep\b|\bvhatsep\b|\bwhats ?ap+\b", "whatsapp"),
       (r"\binstagrama?\b", "instagram"), (r"\bkaimra\b|\bkaimera\b", "camera"), (r"\bsetinga?s?\b", "settings"), (r"\bdaunalod\b", "download"), (r"\bmyujik\b|\bmyuzik\b", "music"),
(r"\bwi[\s-]?fi\b|\bwify\b|\bwaifai\b|\bwifai\b", "wifi"), (r"\bblue[\s-]tooth\b|\bblutooth\b|\bbluetoth\b|\bblututh\b", "bluetooth"),
       (r"\bbandh\b|\bbund\b", "band"), (r"\bsade\b|\bsadhe\b|\bsaadhe\b", "saade"), (r"\bsavva\b", "sava"), (r"\bpaone\b|\bpone\b", "paune"),
       (r"\bba+je\b|\bbje\b|\bbaj\b", "baje"), (r"\bvaj(?:a)?ta\b|\bvajata\b|\bvajta\b|\bwajta\b", "baje"), (r"\bsakali\b|\bsakal\b|\bsakaali\b|\bsubha\b|\bsubeh\b", "subah"),
       (r"\bsandhyakali\b|\bsham\b|\bsaam\b", "shaam"), (r"\bratri\b|\brat\b", "raat"), (r"\bdupari\b|\bdopehar\b|\bdupahar\b", "dopahar"),
       (r"\bmins?\b|\bminat\b|\bminit\b|\bminutes\b|\bminute\b|\bminton\b", "minute"), (r"\bhours?\b|\bghanta\b|\bghante\b|\bghanto\b|\btas\b", "ghante"),
       (r"\bnantar\b|\bbaadh\b|\bbad\b", "baad"), (r"\bday after tomorrow\b", "parso"), (r"\btomorrow\b", "kal"), (r"\btoday\b", "aaj"),
       (r"\bawaz\b|\baawaz\b|\baavaj\b|\bawaj\b|\baawaj\b", "awaaz")]
NUMW = {"ek": 1, "do": 2, "don": 2, "teen": 3, "tin": 3, "char": 4, "chaar": 4, "paanch": 5, "panch": 5, "pach": 5, "chhe": 6, "che": 6, "chheh": 6, "saha": 6, "chah": 6,
        "saat": 7, "sat": 7, "aath": 8, "ath": 8, "nau": 9, "nou": 9, "das": 10, "daha": 10, "gyarah": 11, "gyara": 11, "akra": 11, "barah": 12, "baarah": 12, "bara": 12, "baara": 12,
        "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
        "pandrah": 15, "bees": 20, "tees": 30, "pachaas": 50, "pachas": 50, "fifty": 50, "twenty": 20, "thirty": 30, "zero": 0, "shunya": 0, "sau": 100, "hundred": 100}
NW = "|".join(sorted(NUMW, key=len, reverse=True))
def numwords(s):
    # only convert number words next to time/number cue words (so messages like "ek baat" stay)
    s = re.sub(rf"\b({NW})(?=\s+(?:baje|minute|ghante|am|pm|number|no\b|percent|%|pe click|dabao|tap))", lambda m: str(NUMW[m.group(1)]), s)
    s = re.sub(rf"\b(saade|sava|paune)\s+({NW})\b", lambda m: f"{m.group(1)} {NUMW[m.group(2)]}", s)
    s = re.sub(rf"\b(volume|awaaz|number)\s+({NW})\b", lambda m: f"{m.group(1)} {NUMW[m.group(2)]}", s)
    s = re.sub(r"\baadhe\s+ghante\b|\badh[ae]\s+ghante\b|\bhalf an? ghante\b|\barddha\s+ghante\b", "30 minute", s)
    s = re.sub(r"\bdedh\s+ghante\b", "90 minute", s)
    return s

def pre(text):
    """Normalize user text before the model: Devanagari, spelling variants, number words. Keeps case of other words."""
    s = deva(text).strip()
    low = s.lower()
    for a, b in FIX: low = re.sub(a, b, low)
    low = numwords(low)
    low = re.sub(r"(\d)\s*(am|pm)\b", r"\1 \2", low)
    low = re.sub(r"\s+", " ", low).strip()
    # restore original capitalization word-by-word where the word itself is unchanged (names)
    ow = s.split(); lw = low.split()
    if len(ow) == len(lw): low = " ".join(o if o.lower() == l else l for o, l in zip(ow, lw))
    return low

# ---------- time / date ----------
def norm_time(x):
    s = x.lower()
    m = re.search(r"(\d+)\s*(minute|ghante)\s*(baad|me|mein|mai|nantar)?\b", s)
    if m and (m.group(3) or re.search(r"\b(in|after)\s+\d+\s*(minute|ghante)", s)) and not re.search(r"\bbaje\b|\d:\d\d|\b(am|pm)\b", s):
        return f"@t+{m.group(1)}{'m' if m.group(2) == 'minute' else 'h'}"
    part = re.search(r"\b(subah|dopahar|shaam|raat|morning|evening|night|afternoon)\b", s)
    part = {"morning": "subah", "afternoon": "dopahar", "evening": "shaam", "night": "raat"}.get(part.group(1), part.group(1)) if part else ""
    ap = re.search(r"\b(am|pm)\b", s); ap = ap.group(1) if ap else ""
    mod = re.search(r"\b(saade|sava|paune)\s+(\d+)\b", s); mm = 0; hh = None
    if mod: hh = int(mod.group(2)); mm = {"saade": 30, "sava": 15, "paune": 45}[mod.group(1)]
    else:
        m = re.search(r"\b(\d{1,2})[:.](\d\d)\b", s) or re.search(r"\b(\d+)\s*bajke\s*(\d+)\s*minute", s)
        if m: hh, mm = int(m.group(1)), int(m.group(2))
        else:
            m = re.search(r"\b(\d{1,2})\s*(?:baje|am|pm|o'?clock)\b", s) or re.search(r"\b(?:at|ko|la)\s+(\d{1,2})\b(?!\s*(?:minute|ghante|number))", s)
            if m: hh = int(m.group(1))
            elif part or ap:
                m = re.search(r"\b(\d{1,2})\b(?!\s*(?:minute|ghante|number|percent|%))", s)
                if m: hh = int(m.group(1))
    if hh is None or hh > 24 or mm > 59: return None
    if mod and mod.group(1) == "paune": hh -= 1
    if ap: H = (hh % 12) + (12 if ap == "pm" else 0); return f"{H:02d}:{mm:02d}"
    if hh > 12: return f"{hh % 24:02d}:{mm:02d}"
    if part:
        H = {"subah": hh % 12, "dopahar": 12 if hh == 12 else (hh % 12) + 12 if hh < 7 or hh == 12 else hh % 12 + (12 if hh < 12 else 0),
             "shaam": hh % 12 + 12, "raat": (hh % 12 + 12) % 24 if hh not in (1, 2, 3, 4) else hh}[part]
        if part == "dopahar": H = 12 if hh == 12 else (hh + 12 if hh <= 6 else hh)
        if part == "raat" and hh == 12: H = 0
        return f"{H % 24:02d}:{mm:02d}"
    return f"{(hh % 12) or 12:02d}:{mm:02d}?"
WD = {"somvar": 0, "somvaar": 0, "monday": 0, "mangalvar": 1, "mangalvaar": 1, "tuesday": 1, "budhvar": 2, "budhvaar": 2, "budhwar": 2, "wednesday": 2,
      "guruvar": 3, "guruvaar": 3, "brihaspativar": 3, "thursday": 3, "shukravar": 4, "shukravaar": 4, "shukrawar": 4, "friday": 4,
      "shanivar": 5, "shanivaar": 5, "shaniwar": 5, "saturday": 5, "ravivar": 6, "ravivaar": 6, "raviwar": 6, "itvaar": 6, "itwar": 6, "sunday": 6}
def norm_date(x):
    s = x.lower()
    for w, d in WD.items():
        if re.search(rf"\b{w}\b", s): return f"@w{d}"
    for w, d in (("parso", 2), ("udya", 1), ("kal", 1), ("aaj", 0)):
        if re.search(rf"\b{w}\b", s): return f"@d+{d}"
    return "@d+0"
