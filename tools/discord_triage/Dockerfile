FROM python:3.12-slim

# Faster, cleaner Python in containers.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY *.py ./

# Run as a non-root user.
RUN useradd --create-home appuser
USER appuser

CMD ["python", "bot.py"]
