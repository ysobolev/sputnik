#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

.PHONY: clean report coverage

SOURCE_FILES := $(wildcard test_*.py)

TESTS = $(basename $(SOURCE_FILES))
COVERAGE = /usr/local/bin/coverage
TRIAL = /usr/local/bin/trial

ALL=$(TESTS_OLD)

all: coverage

clean:
	rm -rf .coverage* htmlcov _trial_temp

report:
	$(COVERAGE) combine
	$(COVERAGE) html
	
coverage: $(TESTS)

$(TESTS):
	$(COVERAGE) run -p --branch --source .. `which trial` $@
