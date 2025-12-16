# Sashko Music - Deployment Guide

Інструкція з розгортання sashko-music на Ubuntu Server.

## 📋 Зміст

- [Архітектура](#архітектура)
- [Вимоги](#вимоги)
- [Крок 1: Підготовка Ubuntu Server](#крок-1-підготовка-ubuntu-server)
- [Крок 2: Підготовка структури директорій](#крок-2-підготовка-структури-директорій)
- [Крок 3: Клонування проекту](#крок-3-клонування-проекту)
- [Крок 4: Налаштування середовища](#крок-4-налаштування-середовища)
- [Крок 5: Встановлення Python CLI Tools](#крок-5-встановлення-python-cli-tools)
- [Крок 6: Налаштування Slskd](#крок-6-налаштування-slskd)
- [Крок 7: Перший запуск](#крок-7-перший-запуск)
- [Корисні команди](#корисні-команди)
- [Troubleshooting](#troubleshooting)

---

## 🏗 Архітектура

Проект складається з 8 сервісів в Docker контейнерах:

### Java мікросервіси (Spring Boot)
- **sm-main-agent** (port 8080) - Telegram бот + оркестрація
- **sm-library-agent** (port 8082) - управління музичною бібліотекою + БД
- **sm-download-agent** (port 8081) - координація завантажень

### Інфраструктура
- **PostgreSQL** (port 5432) - база даних для метаданих
- **Redpanda** (port 9092) - Kafka для міжсервісної комунікації
- **Redpanda Console** (port 9094) - веб UI для моніторингу Kafka

### Додаткові сервіси
- **Slskd** (port 5030) - Soulseek P2P клієнт
- **Navidrome** (port 4533) - музичний стрімінг сервер

---

## 🔧 Вимоги

### Апаратні
- CPU: 2+ cores
- RAM: 4GB+ (рекомендовано 8GB)
- Диск: 20GB+ для системи, окремий диск для музики

### Програмні
- Ubuntu Server 22.04 LTS або новіше
- Docker або Podman
- Git
- Python 3.8+
- SSH доступ з правами sudo

---

## Крок 1: Підготовка Ubuntu Server

### 1.1 Оновлення системи

```bash
sudo apt update
sudo apt upgrade -y
```

### 1.2 Встановлення Docker

```bash
# Встановлення Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Додати користувача до групи docker (щоб не потрібно sudo)
sudo usermod -aG docker $USER

# Перелогінитися або виконати
newgrp docker

# Перевірка
docker --version
```

### 1.3 Встановлення Docker Compose

```bash
sudo apt install docker-compose-plugin -y

# Перевірка
docker compose version
```

**Альтернатива: Podman** (якщо хочеш використовувати Podman замість Docker)

```bash
sudo apt install podman podman-compose -y
podman --version
```

### 1.4 Встановлення Git та Python

```bash
sudo apt install git python3 python3-pip python3-venv -y

git --version
python3 --version
```

---

## Крок 2: Підготовка структури директорій

```bash
# Створити директорію для проекту
sudo mkdir -p /opt/sashko-music
sudo chown $USER:$USER /opt/sashko-music

# Створити директорії для завантажень
mkdir -p /opt/sashko-music/downloads/{slskd,qobuz,bandcamp,apple-music}

# Створити директорію для Navidrome
mkdir -p /opt/sashko-music/navidrome/data

# Створити директорію для Slskd конфігурації
mkdir -p /opt/sashko-music/slskd/{app,config,downloads,incomplete}

# Переконатися що твоя музична бібліотека примонтована
ls -la /mnt/media_hdd/lib
```

---

## Крок 3: Клонування проекту

```bash
cd /opt/sashko-music

# Клонувати головний репозиторій
# ЗАМІСТЬ цього створіть новий репо sashko-music на GitHub
# і запуште туди файли: Dockerfile, docker-compose.yaml, deploy.sh, тощо

git clone --recurse-submodules git@github.com:YourUsername/sashko-music.git .

# Або якщо репо вже клоновано без submodules:
git submodule init
git submodule update
```

**Важливо:** Переконайся що твій SSH ключ доданий до GitHub:
```bash
ssh-keygen -t ed25519 -C "your_email@example.com"
cat ~/.ssh/id_ed25519.pub
# Додай цей ключ на GitHub: Settings -> SSH Keys
```

---

## Крок 4: Налаштування середовища

### 4.1 Вибрати конфігурацію

Проект має два готові environment файли:

- **`.env.development`** - для локальної розробки (MacBook)
  - Library: `/Users/okravch/my/sm/lib`
  - Downloads: `/Users/okravch/my/sm/sm-download-agent/downloads`
  - Webhook: `http://host.docker.internal:8081` (for Docker → IntelliJ communication)

- **`.env.production`** - для production сервера (Ubuntu Server)
  - Library: `/mnt/media_hdd/lib`
  - Downloads: `/opt/sashko-music/downloads`
  - Webhook: `http://sm-download-agent:8081` (Docker → Docker communication)

### 4.2 Створити .env файл

**На production сервері:**
```bash
cd /opt/sashko-music
cp .env.production .env
nano .env
```

**Для локальної розробки:**
```bash
cd /Users/okravch/my/sm
cp .env.development .env
# Вже містить твої MacBook шляхи, просто перевір креди
```

### 4.3 Заповнити обов'язкові змінні

Відкрий `.env` файл і **на production** заповни:

**ОБОВ'ЯЗКОВО:**
- `TGBOT_NAME` - ім'я твого Telegram бота
- `TGBOT_TOKEN` - токен від @BotFather
- `SLSKD_API_KEY` - з slskd конфігу (можна залишити дефолтний)

**Опціонально (але рекомендовано):**
- `DISCOGS_API_TOKEN` - для метаданих музики
- `AI_ANTHROPIC_API_KEY` - для AI розпізнавання релізів
- `QOBUZ_EMAIL` / `QOBUZ_PASSWORD` - якщо є Qobuz
- `NAVIDROME_USER_ID` / `NAVIDROME_GROUP_ID` - виконай `id -u` та `id -g`

Збережи файл: `Ctrl+O`, `Enter`, `Ctrl+X`

---

## Крок 5: Встановлення Python CLI Tools

```bash
cd /opt/sashko-music
./setup-python-tools.sh
```

Цей скрипт встановить:
- `qobuz-dl` - для Qobuz
- `gamdl` - для Apple Music
- `bandcamp-dl` - для Bandcamp

**Після встановлення:**
1. Перевір що PATH оновлено (може потрібно `source ~/.bashrc`)
2. Оновi `.env` файл шляхами до CLI tools (скрипт покаже правильні шляхи)

---

## Крок 6: Налаштування Slskd

### 6.1 Створити конфігураційний файл

```bash
nano /opt/sashko-music/slskd/config/slskd.yml
```

Вставити базову конфігурацію:

```yaml
soulseek:
  username: your_soulseek_username
  password: your_soulseek_password

web:
  authentication:
    username: slskd
    password: slskd

  http:
    port: 5030

global:
  upload:
    slots: 10
  download:
    slots: 10

shares:
  directories:
    - /music

downloads:
  directory: /var/slskd/downloads
  incomplete_directory: /var/slskd/incomplete

integration:
  webhooks:
    - url: http://host.containers.internal:8081/slskd/download-complete
      events:
        - download_complete
```

Збережи: `Ctrl+O`, `Enter`, `Ctrl+X`

---

## Крок 7: Перший запуск

### 7.1 Запустити деплой скрипт

```bash
cd /opt/sashko-music
./deploy.sh
```

Цей скрипт:
1. Перевірить що все налаштовано
2. Побілдить Docker образи (може зайняти 5-10 хвилин)
3. Запустить всі сервіси
4. Покаже статус

### 7.2 Перевірити що все працює

```bash
# Подивитися статус контейнерів
docker compose ps

# Подивитися логи
docker compose logs -f sm-main-agent
docker compose logs -f sm-library-agent
docker compose logs -f sm-download-agent
```

### 7.3 Відкрити веб інтерфейси

- **Navidrome**: http://your-server-ip:4533
- **Slskd**: http://your-server-ip:5030
- **Redpanda Console**: http://your-server-ip:9094

### 7.4 Почати спілкування з Telegram ботом

Знайди свого бота в Telegram та надішли `/start`

---

## 🛠 Корисні команди

### Деплой та оновлення

```bash
# Оновити код і перезапустити все
./deploy.sh

# Оновити тільки submodules без перезапуску
git submodule update --remote --merge

# Оновити конкретний submodule
cd sm-main-agent && git pull origin main && cd ..
```

### Управління сервісами

```bash
# Подивитися статус
docker compose ps

# Перезапустити конкретний сервіс
docker compose restart sm-main-agent

# Зупинити все
docker compose down

# Запустити все
docker compose up -d

# Пересібілдити і запустити
docker compose up -d --build
```

### Логи

```bash
# Всі логи
docker compose logs

# Логи конкретного сервісу (follow mode)
docker compose logs -f sm-main-agent

# Останні 100 рядків
docker compose logs --tail=100 sm-library-agent

# Логи з часовими мітками
docker compose logs -t sm-download-agent
```

### Очистка

```bash
# Видалити зупинені контейнери
docker compose down

# Видалити контейнери та volumes (ВИДАЛИТЬ БД!)
docker compose down -v

# Видалити невикористовувані образи
docker image prune -a
```

### База даних

```bash
# Підключитися до PostgreSQL
docker exec -it sm_postgres psql -U postgres -d sm_library

# Бекап БД
docker exec sm_postgres pg_dump -U postgres sm_library > backup.sql

# Відновлення БД
docker exec -i sm_postgres psql -U postgres sm_library < backup.sql
```

---

## 🔍 Troubleshooting

### Проблема: Сервіс не стартує

```bash
# Подивитися логи
docker compose logs [service_name]

# Перевірити що .env файл правильний
cat .env | grep TOKEN

# Перевірити що порти не зайняті
sudo netstat -tulpn | grep 8080
```

### Проблема: Git submodule помилка

```bash
# Переініціалізувати submodules
git submodule deinit -f .
git submodule update --init --recursive
```

### Проблема: Docker build fails

```bash
# Очистити Docker cache
docker builder prune -a

# Пересібілдити без кешу
docker compose build --no-cache
```

### Проблема: Permission denied на volumes

```bash
# Перевірити права
ls -la /mnt/media_hdd/lib
ls -la /opt/sashko-music/downloads

# Виправити права
sudo chown -R $USER:$USER /opt/sashko-music
```

### Проблема: Slskd не може підключитися до sm-download-agent

Переконайся що в `slskd.yml` webhook URL використовує `host.containers.internal`:
```yaml
webhooks:
  - url: http://host.containers.internal:8081/slskd/download-complete
```

### Проблема: Python CLI tools не знайдено

```bash
# Перевірити PATH
echo $PATH

# Додати до PATH
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc

# Перевірити інсталяцію
which qobuz-dl
which gamdl
which bandcamp-dl
```

---

## 📞 Підтримка

Якщо виникли проблеми:
1. Перевір логи: `docker compose logs -f [service_name]`
2. Перевір .env конфігурацію
3. Перевір що всі порти доступні
4. Перевір що volumes правильно примонтовані

---

## 🚀 Готово!

Тепер твій Sashko Music працює на сервері. Можеш:
- Керувати музикою через Telegram бота
- Слухати музику через Navidrome
- Завантажувати через Slskd, Qobuz, Apple Music, Bandcamp
- Моніторити систему через Redpanda Console

**Enjoy your music! 🎵**
