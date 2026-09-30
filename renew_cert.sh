#!/bin/bash
# Colin投屏 - 证书续期（WSL 侧）
# 用途: certbot 用 deSEC DNS-01 续期自己的域名证书，并拷贝到 Windows 中转目录
#
# ⚠️ 凭据安全：deSEC API token 不允许出现在本文件或任何提交内容中。
#   读取顺序：环境变量 DESEC_TOKEN > DESC_TOKEN（历史名，兼容）> ~/.desec-token（权限 600，勿提交）。
#   泄露过的旧 token 必须在 deSEC 控制台撤销并重新生成。
#
# 输出目录：由调用方通过环境变量 CERT_OUT 显式传入（一键续期打包.bat 用 wslpath 把
#   Windows %TEMP%\NavMirror-certs 转成 WSL 路径后传入）。不传入时默认 /tmp/certs，
#   绝不在 Shell 里用 $USER 猜 Windows 用户目录（WSL 用户名 ≠ Windows 用户名）。
#
# 本文件必须是 LF 行结尾（.gitattributes 已强制 *.sh eol=lf；CRLF 会让 bash 报语法错）。
set -euo pipefail
umask 077

DOMAIN="${NAVMIRROR_DOMAIN:-}"
if [[ ! "$DOMAIN" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]] ||
   [[ "$DOMAIN" != *.* || "$DOMAIN" == *..* || "$DOMAIN" == "example.invalid" ]]; then
  echo "错误：请设置 NAVMIRROR_DOMAIN 为自己的证书域名（不含 https://、端口或路径）。" >&2
  exit 1
fi

# WSL 侧 certbot 通常装在用户目录；若无则用系统路径
[ -d "$HOME/.local/bin" ] && export PATH="$HOME/.local/bin:$PATH"

TOKEN=""
if [ -n "${DESEC_TOKEN:-}" ]; then
  TOKEN="$DESEC_TOKEN"
elif [ -n "${DESC_TOKEN:-}" ]; then
  TOKEN="$DESC_TOKEN"
elif [ -f "$HOME/.desec-token" ]; then
  TOKEN="$(tr -d '[:space:]' < "$HOME/.desec-token")"
fi
if [ -z "$TOKEN" ]; then
  echo "错误：未找到 deSEC token。" >&2
  echo "请设置环境变量 DESEC_TOKEN，或创建 ~/.desec-token（内容只有 token 本体，chmod 600）。" >&2
  echo "token 在 deSEC 控制台生成，并限定到自己的域名。" >&2
  exit 1
fi

CREDS="$(mktemp "${TMPDIR:-/tmp}/navmirror-desec.XXXXXX")"
# 无论成功失败都清理凭据临时文件（pipefail 保证 certbot 失败时这里也会被执行）
trap 'rm -f "$CREDS"' EXIT
printf 'dns_desec_token = %s\n' "$TOKEN" > "$CREDS"
chmod 600 "$CREDS"

OUT="${CERT_OUT:-/tmp/certs}"
mkdir -p "$OUT"
CERTBOT_HOME="${CERTBOT_HOME:-/tmp/certbot}"
LIVE="$CERTBOT_HOME/conf/live/$DOMAIN"
if [ ! -f "$LIVE/fullchain.pem" ] || [ ! -f "$LIVE/privkey.pem" ]; then
  echo "错误：尚未签发 $DOMAIN 的证书，请先按 docs/SETUP.md 完成首次签发。" >&2
  exit 1
fi

echo "== 1/2 续期证书 (Let's Encrypt via deSEC) =="
# pipefail：certbot 失败即整体失败，不会出现"复制旧证书却报成功"
certbot renew --cert-name "$DOMAIN" --dns-desec-credentials "$CREDS" \
  --config-dir "$CERTBOT_HOME/conf" --work-dir "$CERTBOT_HOME/work" --logs-dir "$CERTBOT_HOME/log" \
  --non-interactive 2>&1 | tail -8

echo "== 2/2 复制到 Windows 中转目录 =="
cp "$LIVE/fullchain.pem" "$OUT/tesla_cert.pem"
cp "$LIVE/privkey.pem"   "$OUT/tesla_key.pem"
echo "完成:"
ls -l "$OUT/tesla_cert.pem" "$OUT/tesla_key.pem"
