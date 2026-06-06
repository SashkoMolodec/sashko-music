FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --upgrade pip setuptools wheel

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY src/ ./src/
COPY analysis_core/ ./analysis_core/

ENV PYTHONPATH=/app
CMD ["python", "-u", "src/main.py"]