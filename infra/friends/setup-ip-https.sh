#!/usr/bin/env bash
# Ubuntu 24.04; run as root. Does not change the database, world or application env.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
ip=${1:?Usage: bash setup-ip-https.sh PUBLIC_IPV4}
[[ $EUID -eq 0 ]] || { echo 'Run as root'; exit 1; }
python3 -c 'import ipaddress,sys; a=ipaddress.IPv4Address(sys.argv[1]); assert a.is_global' "$ip"
curl -fsS http://127.0.0.1:3000/api/v1/health/ready
apt-get update
apt-get install -y nginx python3-venv ca-certificates
if [[ ! -x /opt/animeoncraft-certbot/bin/certbot ]]; then
  python3 -m venv /opt/animeoncraft-certbot
fi
/opt/animeoncraft-certbot/bin/pip install 'certbot>=5.4,<6'
/opt/animeoncraft-certbot/bin/certbot --version
mkdir -p /var/www/animeoncraft-acme/.well-known/acme-challenge
site=/etc/nginx/sites-available/animeoncraft-api
# Keep an existing TLS configuration in service during a repeated run.
if [[ ! -e "$site" ]]; then
  cat > "$site" <<EOF
server {
    listen 80;
    server_name $ip;
    location ^~ /.well-known/acme-challenge/ { root /var/www/animeoncraft-acme; }
    location / { return 404; }
}
EOF
fi
ln -sfn "$site" /etc/nginx/sites-enabled/animeoncraft-api
nginx -t
systemctl enable --now nginx
systemctl reload nginx
# Never enable a firewall here: preserve the owner's SSH access.
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
  ufw allow 80/tcp
  ufw allow 443/tcp
  ufw allow 25565/tcp
fi
mkdir -p /etc/letsencrypt/renewal-hooks/deploy
cat > /etc/letsencrypt/renewal-hooks/deploy/animeoncraft-nginx <<'EOF'
#!/bin/sh
set -eu
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
EOF
chmod 755 /etc/letsencrypt/renewal-hooks/deploy/animeoncraft-nginx
/opt/animeoncraft-certbot/bin/certbot certonly --non-interactive \
  --agree-tos --register-unsafely-without-email \
  --server https://acme-v02.api.letsencrypt.org/directory \
  --preferred-profile shortlived --webroot \
  --webroot-path /var/www/animeoncraft-acme --ip-address "$ip" --cert-name "$ip"
cp -a "$site" "$site.before-https-$(date +%Y%m%d%H%M%S)"
cat > "$site" <<EOF
server {
    listen 80;
    server_name $ip;
    location ^~ /.well-known/acme-challenge/ { root /var/www/animeoncraft-acme; }
    location / { return 308 https://$ip\$request_uri; }
}
server {
    listen 443 ssl;
    server_name $ip;
    ssl_certificate /etc/letsencrypt/live/$ip/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$ip/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    client_max_body_size 2m;
    location ~ ^/api/v1/game/admission(?:/|$) { return 404; }
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$remote_addr;
        proxy_set_header X-Forwarded-Proto https;
    }
}
EOF
nginx -t
systemctl reload nginx
cat > /etc/systemd/system/animeoncraft-certbot.service <<'EOF'
[Unit]
Description=Renew AnimeOnCraft IP HTTPS certificate
Wants=network-online.target
After=network-online.target
[Service]
Type=oneshot
ExecStart=/opt/animeoncraft-certbot/bin/certbot renew --quiet
EOF
cat > /etc/systemd/system/animeoncraft-certbot.timer <<'EOF'
[Unit]
Description=Check AnimeOnCraft certificate renewal twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=3600
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now animeoncraft-certbot.timer
curl --resolve "$ip:443:127.0.0.1" -fsS "https://$ip/api/v1/health/ready"
code=$(curl --resolve "$ip:443:127.0.0.1" -sS -o /dev/null -w '%{http_code}' "https://$ip/api/v1/game/admission/redeem")
[[ "$code" == 404 ]] || { echo "Unexpected admission response: $code"; exit 1; }
systemctl list-timers animeoncraft-certbot.timer --no-pager
echo 'AOC_HTTPS_OK (local TLS verified; external access still needs checking)'
