FROM ubuntu:jammy

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        software-properties-common \
        ca-certificates \
        gnupg \
    && add-apt-repository -y ppa:deadsnakes/ppa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        python3.11 \
        python3.11-dev \
        python3.11-venv \
        libffi-dev \
        build-essential \
    && python3.11 -m pip install --no-cache-dir --upgrade pip cffi \
    && rm -rf /var/lib/apt/lists/*

CMD ["python3.11"]
