import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Query
from fastapi.responses import JSONResponse

import bandcamp as bc
from config import PORT

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    await bc.init_browser()
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/bandcamp/search")
async def bandcamp_search(q: str = Query(..., min_length=1)):
    results = await bc.search_bandcamp(q)
    return JSONResponse(content=results)


@app.get("/health")
async def health():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=PORT)
