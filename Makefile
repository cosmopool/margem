# Margem: the commands of the project. `make` alone lists them.
# Debug = Margem-Dev (com.kaiodelphino.margem.dev, repo key); release = Margem (key in local.properties, D39).

GRADLE  := ./gradlew
DEV_APP := com.kaiodelphino.margem.dev
APK_DIR := androidApp/build/outputs
# Test filter: make test T=RecadoEdgeTest runs only the classes whose name contains it.
FILTER  := $(if $(T),--tests '*$(T)*',)

.DEFAULT_GOAL := help
.PHONY: help debug install run catalog release bundle test test-shared test-android ios mutation check clean

help: ## List the commands
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  make %-13s %s\n", $$1, $$2}'
	@echo "  T=Name         with test, test-shared, test-android: only test classes whose name contains Name"

debug: ## Build the Margem-Dev APK
	$(GRADLE) :androidApp:assembleDebug
	@ls $(APK_DIR)/apk/debug/*.apk

install: ## Build and install Margem-Dev on the connected device or emulator
	$(GRADLE) :androidApp:installDebug

run: install ## Install Margem-Dev and open it
	adb shell am start -n $(DEV_APP)/com.kaiodelphino.margem.ui.MainActivity

catalog: install ## Install Margem-Dev and open the component catalog
	adb shell am start -n $(DEV_APP)/com.kaiodelphino.margem.CatalogActivity

release: ## Build the release APK (signed when local.properties has release.*)
	$(GRADLE) :androidApp:assembleRelease
	@ls $(APK_DIR)/apk/release/*.apk

bundle: ## Build the release AAB for the Play Store
	$(GRADLE) :androidApp:bundleRelease
	@ls $(APK_DIR)/bundle/release/*.aab

test: test-shared test-android ## Run every test (shared on the JVM, androidApp on Robolectric)

test-shared: ## Run the shared tests on the JVM (Db, budgetMonth, extraction, contract rounds)
	$(GRADLE) :shared:jvmTest $(FILTER)

test-android: ## Run the androidApp tests on Robolectric (UI, listener)
	$(GRADLE) :androidApp:testDebugUnitTest $(FILTER)

ios: ## Compile shared and its tests for the iOS simulator (no iOS app in v1)
	$(GRADLE) :shared:compileKotlinIosSimulatorArm64 :shared:compileTestKotlinIosSimulatorArm64

mutation: ## Mutation testing of shared on the JVM (PIT, Kotlin + SQL mutants); fails below the threshold (D48)
	$(GRADLE) :shared:mutation
	@echo "  report: shared/build/reports/pitest/index.html"

check: test ios debug ## Everything a block must pass: tests, iOS compile, debug build (mutation runs apart: make mutation, D48)

clean: ## Delete build outputs
	$(GRADLE) clean
