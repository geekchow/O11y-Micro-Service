#!/bin/sh
# Open-loop load generator: fires checkouts at a fixed rate WITHOUT waiting
# for responses (real traffic doesn't slow down because you got slow).
# ~10% of amounts end in 7 → declined by payment → a steady stream of
# error traces for the tail sampler to keep.
GATEWAY_URL="${GATEWAY_URL:-http://gateway:8080}"
RPS="${LOAD_RPS:-25}"

until curl -sf "$GATEWAY_URL/health" >/dev/null 2>&1; do
  echo "waiting for gateway at $GATEWAY_URL ..."
  sleep 2
done
echo "gateway is up — open-loop load at ~$RPS req/s"

interval=$(awk -v r="$RPS" 'BEGIN { printf "%.4f", 1/r }')
i=0
while true; do
  i=$((i + 1))
  amt=$(((i * 7) % 100 + 1))
  cart=$((i % 5 + 1))
  curl -s -o /dev/null -m 15 -X POST "$GATEWAY_URL/checkout" \
    -H 'Content-Type: application/json' \
    -d "{\"cartId\":\"$cart\",\"amountCents\":$amt,\"tenant\":\"acme\"}" &
  if [ $((i % 500)) -eq 0 ]; then
    echo "sent $i checkouts"
  fi
  sleep "$interval"
done
