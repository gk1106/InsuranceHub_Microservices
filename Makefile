.PHONY: up down logs sample keys

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f

sample:
	scripts/send-sample.sh $(CODE)

keys:
	scripts/gen-dev-keys.sh
