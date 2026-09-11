FROM python:3.12-slim

WORKDIR /srv
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY fetch-models.sh .
# Models are fetched at build time rather than committed: they are large, and their
# licence belongs to whoever deploys this.
RUN bash fetch-models.sh

# No volumes and no writable state: this service keeps nothing.
USER nobody
EXPOSE 10001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "10001"]
