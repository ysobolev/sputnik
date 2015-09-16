#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

import sys
import twisted.python.log
import twisted.python.util

class SputnikObserver(log.FileLogObserver):
    levels = {10: "DEBUG", 20:"INFO", 30:"WARN", 40:"ERROR", 50:"CRITICAL"}

    def __init__(self, level=20):
        self.level = level
        twisted.python.log.FileLogObserver.__init__(self, sys.stdout)

    def emit(self, eventDict):
        text = twisted.python.log.textFromEventDict(eventDict)
        if text is None:
            return
        
        level = eventDict.get("level", 20)
        if level < self.level:
            return

        timeStr = self.formatTime(eventDict['time'])
        fmtDict = {'system': eventDict['system'],
                   'text': text.replace("\n", "\n\t"),
                   'level': self.levels[level]}
        msgStr = twisted.python.log._safeFormat("%(level)s [%(system)s] %(text)s\n", fmtDict)

        twisted.python.util.untilConcludes(self.write, timeStr + " " + msgStr)
        twisted.python.util.untilConcludes(self.flush)

class Logger:
    def __init__(self, prefix):
        self.prefix = prefix

    def debug(self, message=None):
        twisted.python.log.msg(message, system=self.prefix, level=10)

    def info(self, message=None):
        twisted.python.log.msg(message, system=self.prefix, level=20)

    def warn(self, message=None):
        twisted.python.log.msg(message, system=self.prefix, level=30)

    def error(self, message=None):
        twisted.python.log.err(message, system=self.prefix, level=40)

    def critical(self, message=None):
        twisted.python.log.err(message, system=self.prefix, level=50)

