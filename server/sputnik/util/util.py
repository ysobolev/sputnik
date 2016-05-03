#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

import sputnik.database.models #as models

__author__ = 'sameer'

from datetime import datetime
import sys
import math
import time
import uuid
from sqlalchemy.orm.exc import NoResultFound
from sqlalchemy import func
from twisted.python import log
import twisted.python.util
from sputnik.rpc.zmq_util import ComponentExport
from sqlalchemy.orm.session import Session
import hashlib
from decimal import Decimal

def session_aware(func):
    def wrapped(self, *args, **kwargs):
        try:
            return func(self, *args, **kwargs)
        finally:
            if isinstance(self, ComponentExport):
                session = self.component.session
            else:
                session = self.session

            if isinstance(session, Session):
                session.rollback()
    return wrapped

def get_locale_template(locale, jinja_env, template):
    locales = [locale, "root"]
    templates = [template.format(locale=locale) for locale in locales]
    t = jinja_env.select_template(templates)
    return t

def get_uid():
    return uuid.uuid4().get_hex()

def malicious_looking(w):
    """

    :param w:
    :returns: bool
    """
    return any(x in w for x in '<>&')

def encode_username(username):
    return hashlib.sha256(username).hexdigest()

