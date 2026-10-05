# Thin wrapper: every target is handled by make.sh.
.DEFAULT_GOAL := build

TARGETS := build jvm web web-serve all test install clean help

.PHONY: $(TARGETS)
$(TARGETS):
	@./make.sh $@
