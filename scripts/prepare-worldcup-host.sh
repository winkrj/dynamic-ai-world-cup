#!/usr/bin/env bash
# Run via SSM on the NEW, approved AL2023 x86 host. Never on an existing project host.
set -euo pipefail
umask 077
fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ $(id -u) == 0 && $(uname -m) == x86_64 ]] || fail 'Requires the approved x86 root host.'
wc_volume=${1:-}
wc_initialize=${2:-}
[[ "$wc_volume" =~ ^vol-[a-f0-9]{17}$ ]] || fail 'Supply the exact retained EBS volume ID.'
[[ -z "$wc_initialize" || "$wc_initialize" == --initialize-empty ]] || fail 'Unknown option.'
wc_serial=${wc_volume//-/}
wc_device=$(readlink -f "/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_$wc_serial")
[[ -b "$wc_device" && "$wc_device" =~ ^/dev/nvme[0-9]+n1$ ]] || fail 'The exact EBS NVMe device was not found.'
[[ $(lsblk -dn -o SERIAL "$wc_device" | tr -d ' ') == "$wc_serial" ]] || fail 'EBS serial mismatch.'
wc_root=$(readlink -f "$(findmnt -n -o SOURCE /)")
[[ "$wc_root" != "$wc_device"* ]] || fail 'Refusing the root disk.'
wc_type=$(blkid -s TYPE -o value "$wc_device" || true)
if [[ -z "$wc_type" ]]; then
    [[ "$wc_initialize" == --initialize-empty ]] || fail 'Blank disk requires explicit initialization approval.'
    [[ $(lsblk -nr -o TYPE "$wc_device" | wc -l) -eq 1 ]] || fail 'Refusing a disk with partitions.'
    [[ -z $(wipefs --no-act --noheadings --output TYPE "$wc_device") ]] || fail 'Refusing a disk with an existing signature.'
    [[ -z $(lsblk -nr -o MOUNTPOINTS "$wc_device" | tr -d '[:space:]') ]] || fail 'Refusing an already mounted disk.'
    mkfs.ext4 -L worldcup-data "$wc_device"
elif [[ "$wc_type" != ext4 ]]; then
    fail 'Existing filesystem is not ext4; no conversion or formatting performed.'
fi
wc_uuid=$(blkid -s UUID -o value "$wc_device")
[[ "$wc_uuid" =~ ^[a-f0-9-]{36}$ ]] || fail 'Missing data filesystem UUID.'
if mountpoint -q /var/lib/worldcup; then
    [[ $(findmnt -n -o UUID /var/lib/worldcup) == "$wc_uuid" ]] || fail 'A different filesystem is mounted at the data path.'
elif [[ -d /var/lib/worldcup && -n $(find /var/lib/worldcup -mindepth 1 -maxdepth 1 -print -quit) ]]; then
    fail 'Refusing to hide pre-existing files beneath the data mount.'
fi
install -d -m 0755 /var/lib/worldcup
wc_mount=$(mktemp)
printf '[Unit]\nDescription=Retained World Cup data EBS\nBefore=docker.service\n\n[Mount]\nWhat=UUID=%s\nWhere=/var/lib/worldcup\nType=ext4\nOptions=defaults,noatime,nodev,nosuid\n\n[Install]\nWantedBy=multi-user.target\n' "$wc_uuid" > "$wc_mount"
install -m 0644 "$wc_mount" /etc/systemd/system/var-lib-worldcup.mount
install -d -m 0755 /etc/systemd/system/docker.service.d
wc_docker_unit=$(mktemp)
printf '[Unit]\nRequires=var-lib-worldcup.mount\nAfter=var-lib-worldcup.mount\n' > "$wc_docker_unit"
install -m 0644 "$wc_docker_unit" /etc/systemd/system/docker.service.d/worldcup-data.conf
systemctl daemon-reload
systemctl enable --now var-lib-worldcup.mount
install -d -m 0700 /etc/worldcup
install -d -m 0755 /opt/worldcup /usr/local/lib/docker/cli-plugins
wc_compose=$(mktemp)
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    https://github.com/docker/compose/releases/download/v5.5.1/docker-compose-linux-x86_64 -o "$wc_compose"
printf 'db1889184726840f75c4f9c001048430d4f25b3be3cb084d3ddd762bc0aed576  %s\n' "$wc_compose" | sha256sum --check --status
install -m 0755 "$wc_compose" /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version
printf '%s\n' 'Data volume mounted and pinned Compose installed. No database or AI started.'
