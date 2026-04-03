.PHONY: help build test clean run lint check install-hooks

GRADLE := ./gradlew
APP_MODULE := gate-iam-backend

## help: affiche cette aide
help:
	@grep -E '^##' Makefile | sed 's/^## //' | column -t -s ':'

## build: compile tous les modules (sans les tests)
build:
	$(GRADLE) build -x test

## test: lance tous les tests
test:
	$(GRADLE) test

## test-arch: lance uniquement les tests d'architecture ArchUnit
test-arch:
	$(GRADLE) :$(APP_MODULE):test --tests "fr.gate.iam.backend.architecture.*"

## clean: supprime les artefacts de build
clean:
	$(GRADLE) clean

## run: démarre l'application Spring Boot
run:
	$(GRADLE) :$(APP_MODULE):bootRun

## check: build + tests + vérifications
check:
	$(GRADLE) check

## deps: affiche l'arbre des dépendances
deps:
	$(GRADLE) :$(APP_MODULE):dependencies

## deps-update: liste les dépendances obsolètes
deps-update:
	$(GRADLE) dependencyUpdates

## install-hooks: installe les git hooks via pre-commit
install-hooks:
	@command -v pre-commit >/dev/null 2>&1 || { echo "pre-commit non trouvé — installez-le : pip install pre-commit"; exit 1; }
	pre-commit install --hook-type commit-msg
	pre-commit install
	@echo "Git hooks installés."
