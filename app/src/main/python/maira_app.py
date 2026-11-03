# Android (Chaquopy) bridge: Maira ka apna brain. Koi Gemini/Qwen/API nahi.
import os, sys, json, datetime, threading
import mairabrain
sys.path.insert(0, os.path.dirname(os.path.abspath(mairabrain.__file__)))
import maira_brain
_chat = None
_lock = threading.Lock()

def _get_chat():
    global _chat
    if _chat is None:
        import maira_chat
        _chat = maira_chat
    return _chat

def warm():
    try: _get_chat()
    except Exception: pass
    return "ok"

def ask(text, now_str):
    now = datetime.datetime.strptime(now_str, "%Y-%m-%d %H:%M:%S")
    with _lock:
        tl = text.strip().lower()
        if __import__("re").fullmatch(r"(hey|hi+|hello|helo|hlo|namaste|namaskar|ram ram|good (morning|evening|night|afternoon)|suprabhat)[ !.,]*(maira|mayra|meera)?[ !.?]*", tl):
            g = "Hi Vilas! Bolo, kya karna hai?"
            if "morning" in tl or "suprabhat" in tl: g = "Good morning Vilas! Bolo, kya karna hai?"
            elif "night" in tl: g = "Good night Vilas! Aaram se so jao."
            return json.dumps({"actions": [], "confirmationRequired": False, "reply": g, "speech": g}, ensure_ascii=False)
        r = maira_brain.run(text, now=now)
        if r is None or (not r.get("actions") and not r.get("reply")) or any(a.get("tool") == "keyboard" and not a.get("parameters", {}).get("text") for a in r.get("actions", [])):
            r = {"actions": [{"tool": "fallback_llm", "parameters": {}}]}
        if any(a.get("tool") == "fallback_llm" for a in r.get("actions", [])):
            try:
                ans = _get_chat().chat(text)
            except Exception as e:
                ans = "Abhi jawab nahi de payi (" + str(e)[:80] + ")."
            r = {"actions": [], "confirmationRequired": False, "reply": ans, "speech": ans}
    return json.dumps(r, ensure_ascii=False)
