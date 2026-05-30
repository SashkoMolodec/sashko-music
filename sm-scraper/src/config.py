import os

PORT = int(os.getenv("PORT", "8091"))
BANDCAMP_TIMEOUT_MS = int(os.getenv("BANDCAMP_TIMEOUT_MS", "30000"))
BANDCAMP_EMAIL = os.getenv("BANDCAMP_EMAIL", "")
BANDCAMP_PASSWORD = os.getenv("BANDCAMP_PASSWORD", "")
BANDCAMP_COOKIES_FILE = os.getenv("BANDCAMP_COOKIES_FILE", "/data/bandcamp_cookies.json")
