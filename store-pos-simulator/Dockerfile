# Build from the repository root (the shared catalog.json lives there):
#   docker build -f store-pos-simulator/Dockerfile -t tb-store-pos-simulator .
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    CATALOG_PATH=/app/catalog.json

WORKDIR /app
RUN groupadd -r -g 1000 app && useradd -r -u 1000 -g app -d /app -s /usr/sbin/nologin app

COPY store-pos-simulator/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY catalog.json ./catalog.json
COPY store-pos-simulator/pos_simulator ./pos_simulator

USER app
ENTRYPOINT ["python", "-m", "pos_simulator"]
CMD ["--count", "10", "--rate", "60"]
