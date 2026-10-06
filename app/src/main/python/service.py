# Runs the bot + local web server inside the app (called from BotService.java)
import os, sys, time, traceback, secrets

def run(priv):
    os.environ["ANDROID_PRIVATE"] = priv
    os.environ["HOME"] = priv                      # bot.py uses ~/agent
    os.environ["HEART_WEB"] = os.path.join(priv, "web")
    os.makedirs(os.path.join(priv, "agent"), exist_ok=True)
    tp = os.path.join(priv, "ui_token.txt")
    if not os.path.exists(tp):
        with open(tp, "w") as f: f.write(secrets.token_hex(16))
    while True:
        try:
            import engine, server
            engine.init()
            server.run()
        except Exception:
            traceback.print_exc()
        time.sleep(10)
