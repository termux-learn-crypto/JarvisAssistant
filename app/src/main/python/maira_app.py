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
        r = maira_brain.run(text, now=now)
        if r is None:
            r = {"actions": [], "confirmationRequired": False, "reply": "Ye samajh nahi aaya, thoda alag tarah likho.", "speech": ""}
        if any(a.get("tool") == "fallback_llm" for a in r.get("actions", [])):
            try:
                ans = _get_chat().chat(text)
            except Exception as e:
                ans = "Abhi jawab nahi de payi (" + str(e)[:80] + ")."
            r = {"actions": [], "confirmationRequired": False, "reply": ans, "speech": ans}
    return json.dumps(r, ensure_ascii=False)
