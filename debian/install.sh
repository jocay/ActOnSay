#!/bin/bash
# 安装范围仅包括 ActOnSay 目录、依赖和 actonsay-bridge.service。
set -euo pipefail
umask 077

case "${1:-}" in
  ""|--non-interactive) ;;
  *) printf 'Usage: bash install.sh [--non-interactive]\n' >&2; exit 2 ;;
esac
if [ "$(id -u)" -ne 0 ]; then
  printf 'ActOnSay bridge installation requires container root.\n' >&2
  exit 1
fi

source_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir=/opt/actonsay-bridge
data_dir=/var/lib/actonsay-bridge
config_dir=/etc/actonsay-bridge

if [ ! -f "$source_dir/requirements.txt" ] || [ ! -d "$source_dir/actonsay_bridge" ] || [ ! -f "$source_dir/systemd/actonsay-bridge.service" ]; then
  printf 'The bridge source bundle is incomplete.\n' >&2
  exit 1
fi
if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
  printf 'A running systemd container is required.\n' >&2
  exit 1
fi

# 仅在缺少系统依赖时访问软件源，重复升级不因软件源暂时不可用而失败。
need_packages=0
command -v python3 >/dev/null 2>&1 || need_packages=1
command -v git >/dev/null 2>&1 || need_packages=1
python3 -c 'import venv, ensurepip' >/dev/null 2>&1 || need_packages=1
[ -s /etc/ssl/certs/ca-certificates.crt ] || need_packages=1
if [ "$need_packages" -eq 1 ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends python3 python3-venv python3-pip ca-certificates git
fi
install -d -m 700 "$project_dir" "$project_dir/releases" "$data_dir" "$config_dir"

# 在服务继续运行时准备完整的新版本。依赖失败不会切换现有服务。
release_dir="$project_dir/releases/$(date -u +%Y%m%dT%H%M%S)-$$"
install -d -m 700 "$release_dir"
cp -a "$source_dir/actonsay_bridge" "$release_dir/"
cp "$source_dir/requirements.txt" "$release_dir/requirements.txt"
if [ -d "$source_dir/skills" ]; then
  cp -a "$source_dir/skills" "$release_dir/"
fi
python3 -m venv "$release_dir/venv"
"$release_dir/venv/bin/python" -m pip install --disable-pip-version-check -r "$release_dir/requirements.txt"

# 配对令牌从不写入安装输出，重复部署保留原值。
if [ ! -f "$config_dir/token" ]; then
  ACTONSAY_PAIRING_FILE="$config_dir/token" python3 - <<'PY'
import os
import secrets
fd = os.open(os.environ['ACTONSAY_PAIRING_FILE'], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w') as output:
    output.write(secrets.token_urlsafe(48) + '\n')
PY
fi
chmod 600 "$config_dir/token"
if [ ! -f "$config_dir/environment" ]; then
  cat > "$config_dir/environment" <<'EOF'
ACTONSAY_DATA_DIR=/var/lib/actonsay-bridge
ACTONSAY_TOKEN_FILE=/etc/actonsay-bridge/token
ACTONSAY_TIMEZONE=Asia/Shanghai
EOF
  chmod 600 "$config_dir/environment"
fi

# 只切换本项目的 current 链接，不触碰容器内其他服务或用户工作文件。
ACTONSAY_RELEASE="$release_dir" ACTONSAY_PROJECT="$project_dir" python3 - <<'PY'
import os
from pathlib import Path
project = Path(os.environ['ACTONSAY_PROJECT'])
temporary = project / ('current.next-' + str(os.getpid()))
os.symlink(os.environ['ACTONSAY_RELEASE'], temporary)
os.replace(temporary, project / 'current')
PY
install -m 644 "$source_dir/systemd/actonsay-bridge.service" /etc/systemd/system/actonsay-bridge.service
systemctl daemon-reload
systemctl enable actonsay-bridge.service
systemctl restart actonsay-bridge.service
printf 'ActOnSay bridge deployed. Pairing token remains in /etc/actonsay-bridge/token.\n'
