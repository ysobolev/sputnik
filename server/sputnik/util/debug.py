#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

import time
from twisted.python import log

def timed(f):
    def wrapped(*args, **kwargs):
        start = time.time()
        result = f(*args, **kwargs)
        stop = time.time()
        log.msg("%s completed in %dms." % (f.__name__, (stop - start) * 1000))
        return result
    return wrapped

