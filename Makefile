COMPOSE := docker compose

up: ## Start Floci + PostgreSQL and wait for health
	./scripts/m1/00_up.sh

down: ## Stop the stack and remove volumes
	$(COMPOSE) down -v

ps: ## Show stack status
	$(COMPOSE) ps

m1: ## Run all M1 spike scenarios in order
	./scripts/m1/00_up.sh
	./scripts/m1/01_s3_sqs_smoke.sh
	./scripts/m1/02_ec2_lifecycle.sh
	./scripts/m1/03_ssm_run_command.sh
	./scripts/m1/04_instance_to_s3.sh
	./scripts/m1/05_script_flow.sh

test: ## Run unit + integration tests (Floci tests skip when the stack is down)
	./mvnw test

.PHONY: up down ps m1 test
