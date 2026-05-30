import asyncio
import json
import logging
import os
import re
from pathlib import Path
from urllib.parse import quote_plus

from bs4 import BeautifulSoup
from playwright.async_api import async_playwright, Browser, BrowserContext
from playwright_stealth import stealth_async

from config import BANDCAMP_TIMEOUT_MS, BANDCAMP_EMAIL, BANDCAMP_PASSWORD, BANDCAMP_COOKIES_FILE

logger = logging.getLogger(__name__)

_browser: Browser | None = None
_context: BrowserContext | None = None


async def init_browser():
    global _browser, _context
    playwright = await async_playwright().start()
    _browser = await playwright.chromium.launch(
        headless=True,
        args=[
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-blink-features=AutomationControlled",
            "--disable-dev-shm-usage",
            "--disable-ipv6",
            "--use-gl=swiftshader",
            "--enable-webgl",
        ],
    )
    _context = await _browser.new_context(
        user_agent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        viewport={"width": 1280, "height": 800},
        locale="en-US",
    )

    # Give container network time to stabilize
    await asyncio.sleep(2)

    cookies_loaded = await _load_cookies()
    if cookies_loaded and await _verify_session():
        logger.info("Restored valid Bandcamp session from saved cookies")
        return

    if BANDCAMP_EMAIL and BANDCAMP_PASSWORD:
        await _login()
    else:
        logger.warning("BANDCAMP_EMAIL/PASSWORD not set — searches will likely fail (bot challenge)")
        page = await _context.new_page()
        await stealth_async(page)
        await page.goto("https://bandcamp.com", wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
        title = await page.title()
        logger.info("Anonymous homepage title: %s", title)
        await page.close()


async def _load_cookies() -> bool:
    if not os.path.exists(BANDCAMP_COOKIES_FILE):
        logger.info("No saved cookies file at %s", BANDCAMP_COOKIES_FILE)
        return False
    try:
        with open(BANDCAMP_COOKIES_FILE) as f:
            cookies = json.load(f)
        if not cookies:
            return False
        await _context.add_cookies(cookies)
        logger.info("Loaded %d cookies from %s", len(cookies), BANDCAMP_COOKIES_FILE)
        return True
    except Exception as e:
        logger.warning("Failed to load cookies from %s: %s", BANDCAMP_COOKIES_FILE, e)
        return False


async def _save_cookies():
    try:
        cookies = await _context.cookies()
        Path(BANDCAMP_COOKIES_FILE).parent.mkdir(parents=True, exist_ok=True)
        with open(BANDCAMP_COOKIES_FILE, "w") as f:
            json.dump(cookies, f)
        logger.info("Saved %d cookies to %s", len(cookies), BANDCAMP_COOKIES_FILE)
    except Exception as e:
        logger.warning("Failed to save cookies: %s", e)


async def _verify_session() -> bool:
    page = await _context.new_page()
    await stealth_async(page)
    try:
        await page.goto("https://bandcamp.com", wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
        title = await page.title()
        # Logged-in state: fan header present, or logout link, or user nav
        is_logged_in = await page.evaluate("""
            !!document.querySelector('#user-nav') ||
            !!document.querySelector('.fan-nav') ||
            !!document.querySelector('a[href*="logout"]') ||
            !!document.querySelector('[data-fan-id]')
        """)
        logger.info("Session verify — title: %s | logged_in: %s", title, is_logged_in)
        return bool(is_logged_in)
    except Exception as e:
        logger.warning("Session verification failed: %s", e)
        return False
    finally:
        await page.close()


async def _login() -> bool:
    logger.info("Logging into Bandcamp as %s", BANDCAMP_EMAIL)
    page = await _context.new_page()
    await stealth_async(page)
    try:
        # Step 1: warm up context with stealth on homepage (passes Fastly)
        for attempt in range(5):
            try:
                await page.goto("https://bandcamp.com", wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
                break
            except Exception as e:
                logger.warning("Homepage load attempt %d failed: %s — retrying in 3s", attempt + 1, e)
                await page.wait_for_timeout(3000)
                if attempt == 4:
                    raise
        title = await page.title()
        logger.info("Homepage title: %s", title)
        await page.close()  # stealth page done

        # Step 2: open login page WITHOUT stealth (stealth blocks Bandcamp's own scripts)
        page = await _context.new_page()
        await page.goto("https://bandcamp.com/login", wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
        await page.wait_for_timeout(4000)

        title = await page.title()
        logger.info("Login page title: %s", title)

        await page.screenshot(path="/app/login_page.png")
        logger.info("Screenshot of login page saved to /app/login_page.png")

        # Dismiss cookie consent modal if present
        try:
            accept_btn = page.locator("button:has-text('Accept all')")
            if await accept_btn.is_visible(timeout=3000):
                await accept_btn.click()
                logger.info("Dismissed cookie consent modal")
                await page.wait_for_timeout(1000)
        except Exception:
            pass

        # Show hidden login form if it exists (older Bandcamp layout)
        has_hidden_form = await page.evaluate("""
            (() => {
                const f = document.querySelector('.login-common-form');
                if (f) { f.style.display = 'block'; return true; }
                return false;
            })()
        """)
        logger.info("login-common-form found and shown: %s", has_hidden_form)
        await page.wait_for_selector("input#username-field", timeout=5000)

        # Interact natively so KO submit binding fires (not force — lets KO handle events)
        await page.fill("input#username-field", BANDCAMP_EMAIL)
        await page.fill("input#password-field", BANDCAMP_PASSWORD)

        # Take screenshot before submit for debugging
        await page.screenshot(path="/app/before_submit.png")
        logger.info("Screenshot before submit saved")

        await page.click("#loginform button[type='submit']")
        logger.info("Submitted login form")

        # Wait for navigation (AJAX login redirects after success)
        try:
            await page.wait_for_url(lambda url: "login" not in url, timeout=15000)
            logger.info("Redirected away from login page")
        except Exception:
            logger.warning("No redirect after 15s — checking page state")
            await page.screenshot(path="/app/after_submit.png")
            logger.info("Screenshot after submit saved")

        title = await page.title()
        current_url = page.url
        safe_url = current_url.split("?")[0]
        logger.info("Post-login — url: %s | title: %s", safe_url, title)

        if "login" in current_url.lower():
            # Still on login page — credentials wrong or challenge blocked it
            logger.error("Login may have failed — still on login page")
            return False

        await _save_cookies()
        logger.info("Bandcamp login successful")
        return True
    except Exception as e:
        logger.error("Bandcamp login failed: %s", e)
        return False
    finally:
        await page.close()


async def get_release_metadata(url: str) -> dict | None:
    if _context is None:
        raise RuntimeError("Browser not initialized")

    logger.info("Fetching Bandcamp release metadata: %s", url)
    page = await _context.new_page()
    await stealth_async(page)
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
        html = await page.content()
    except Exception as e:
        logger.warning("Failed to load Bandcamp release page %s: %s", url, e)
        return None
    finally:
        await page.close()

    soup = BeautifulSoup(html, "html.parser")
    return _parse_release_page(soup, url)


def _parse_release_page(soup, url: str) -> dict:
    artist = "Unknown Artist"
    meta_site = soup.select_one("meta[property='og:site_name']")
    if meta_site and meta_site.get("content"):
        artist = meta_site["content"]
    else:
        span = soup.select_one("span[itemprop='byArtist']")
        if span:
            artist = span.get_text(strip=True)
        else:
            link = soup.select_one("p#band-name-location span.title")
            if link:
                artist = link.get_text(strip=True)

    title = "Unknown Title"
    h2 = soup.select_one("h2.trackTitle")
    if h2 and h2.get_text(strip=True):
        title = h2.get_text(strip=True)
    else:
        meta_title = soup.select_one("meta[property='og:title']")
        if meta_title and meta_title.get("content"):
            title = meta_title["content"].split(", by ")[0]

    year = ""
    meta_date = soup.select_one("meta[itemprop='datePublished']")
    if meta_date and meta_date.get("content"):
        date_val = meta_date["content"]
        if len(date_val) >= 4 and date_val[:4].isdigit():
            year = date_val[:4]
    if not year:
        credits = soup.select_one("div.tralbum-credits")
        if credits:
            m = re.search(r"\b(\d{4})\b", credits.get_text())
            if m:
                year = m.group(1)

    image_url = ""
    meta_img = soup.select_one("meta[property='og:image']")
    if meta_img and meta_img.get("content"):
        image_url = meta_img["content"]
    else:
        img = soup.select_one("#tralbumArt img")
        if img:
            image_url = img.get("src", "")

    tags = [a.get_text(strip=True).lower() for a in soup.select("a.tag") if a.get_text(strip=True)]

    tracks = []
    n = 1
    for row in soup.select("table.track_list tr.track_row_view"):
        title_el = row.select_one("span.track-title")
        if not title_el:
            continue
        track_title = title_el.get_text(strip=True)
        if not track_title:
            continue
        track_artist = artist
        if " - " in track_title:
            dash = track_title.index(" - ")
            possible_artist = track_title[:dash].strip()
            possible_title = track_title[dash + 3:].strip()
            if possible_artist and len(possible_artist) < 100 and possible_title:
                track_artist = possible_artist
                track_title = possible_title
        tracks.append({"number": n, "artist": track_artist, "title": track_title})
        n += 1

    track_count = len(tracks)
    release_type = "Album" if track_count > 1 else "Track"

    logger.info("Parsed release: %s - %s (%s), %d tracks, %d tags", artist, title, year, track_count, len(tags))
    return {
        "artist": artist,
        "title": title,
        "year": year,
        "imageUrl": image_url,
        "tags": tags,
        "type": release_type,
        "trackCount": track_count,
        "tracks": tracks,
    }


async def get_first_result_html(query: str) -> str | None:
    if _context is None:
        raise RuntimeError("Browser not initialized")
    url = f"https://bandcamp.com/search?q={quote_plus(query)}"
    page = await _context.new_page()
    await stealth_async(page)
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)
        await page.wait_for_selector("li.searchresult", timeout=15000)
        html = await page.content()
    except Exception as e:
        logger.warning("debug-html failed: %s", e)
        return None
    finally:
        await page.close()
    from bs4 import BeautifulSoup
    soup = BeautifulSoup(html, "html.parser")
    first = soup.select_one("li.searchresult")
    return first.prettify() if first else None


async def search_bandcamp(query: str) -> list[dict]:
    if _context is None:
        raise RuntimeError("Browser not initialized")

    logger.info("Bandcamp search: q=%s", query)
    url = f"https://bandcamp.com/search?q={quote_plus(query)}"

    page = await _context.new_page()
    await stealth_async(page)
    try:
        await page.goto(url, wait_until="domcontentloaded", timeout=BANDCAMP_TIMEOUT_MS)

        try:
            await page.wait_for_selector("li.searchresult", timeout=15000)
        except Exception:
            title = await page.title()
            logger.warning("No search results appeared — page title: %s | url: %s", title, page.url)
            return []

        html = await page.content()
    except Exception as e:
        logger.warning("Bandcamp search page load failed for '%s': %s", query, e)
        return []
    finally:
        await page.close()

    soup = BeautifulSoup(html, "html.parser")
    items = soup.select("li.searchresult")
    logger.info("Found %d li.searchresult elements", len(items))

    results = []
    for item in items:
        result = _parse_result(item)
        if result:
            results.append(result)

    logger.info("Bandcamp search returned %d results for '%s'", len(results), query)
    return results


def _parse_result(item) -> dict | None:
    try:
        type_el = item.select_one("div.itemtype")
        item_type = type_el.get_text(strip=True).lower() if type_el else "unknown"
        if item_type not in ("album", "track"):
            return None

        title_el = item.select_one("div.heading a")
        if not title_el:
            return None
        title = title_el.get_text(strip=True)
        url = title_el.get("href", "")
        if not title or not url:
            return None
        url = re.sub(r"\?.*", "", url)

        artist_el = item.select_one("div.subhead")
        artist_raw = artist_el.get_text(separator=" ", strip=True) if artist_el else ""
        if "by " in artist_raw:
            artist = artist_raw.split("by ", 1)[-1].strip()
        else:
            artist = artist_raw

        img_el = item.select_one("img")
        image_url = img_el.get("src", "") if img_el else ""
        if image_url:
            image_url = image_url.replace("_7.jpg", "_16.jpg")

        year = ""
        released_el = item.select_one("div.released")
        if released_el:
            m = re.search(r"\b(\d{4})\b", released_el.get_text())
            if m:
                year = m.group(1)

        tag_el = item.select_one("div.tags")
        if tag_el:
            raw = tag_el.get_text(separator=" ", strip=True)
            raw = re.sub(r"^tags\s*:?\s*", "", raw, flags=re.IGNORECASE)
            tags = [t.strip().lower() for t in raw.split(",") if len(t.strip()) > 2]
        else:
            tags = []

        return {
            "type": item_type,
            "artist": artist,
            "title": title,
            "url": url,
            "imageUrl": image_url,
            "year": year,
            "tags": tags,
        }
    except Exception as e:
        logger.warning("Error parsing result: %s", e)
        return None
