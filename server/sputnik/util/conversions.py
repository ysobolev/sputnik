#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

__author__ = 'sameer'

from datetime import datetime
import math
from decimal import Decimal

def price_to_wire(contract, price):
    if contract.contract_type == "cash_pair":
        price = price * contract.denominated_contract.denominator * contract.denominator
    else:
        price = price * contract.denominator

    p = price - price % contract.tick_size
    if p != int(p):
        raise Exception("price_to_wire returns non-integer value")
    else:
        return int(p)

def price_from_wire(contract, price):
    if contract.contract_type == "cash_pair":
        return Decimal(price) / (contract.denominated_contract.denominator * contract.denominator)
    else:
        return Decimal(price) / contract.denominator

def quantity_from_wire(contract, quantity):
    if contract.contract_type == "prediction" or contract.contract_type == "futures":
        return quantity
    elif contract.contract_type == "cash":
        return Decimal(quantity) / contract.denominator
    else:
        return Decimal(quantity) / contract.payout_contract.denominator

def quantity_to_wire(contract, quantity):
    if contract.contract_type == "prediction" or contract.contract_type == "futures":
        q = quantity
    elif contract.contract_type == "cash":
        q = quantity * contract.denominator
    else:
        quantity = quantity * contract.payout_contract.denominator
        q = quantity - quantity % contract.lot_size

    if q != int(q):
        raise Exception("quantity_to_wire returns non-integer value")
    else:
        return int(q)

def get_precision(numerator, denominator):
    if numerator <= denominator:
        return 0
    else:
        return math.log10(numerator / denominator)

def get_price_precision(contract):
    if contract.contract_type == "cash_pair":
        return get_precision(contract.denominated_contract.denominator * contract.denominator, contract.tick_size)
    else:
        return get_precision(contract.denominator, contract.tick_size)

def get_quantity_precision(contract):
    if contract.contract_type == "prediction" or contract.contract_type == "futures":
        return 0
    elif contract.contract_type == "cash":
        return get_precision(contract.denominator, contract.lot_size)
    else:
        return get_precision(contract.payout_contract.denominator, contract.lot_size)

def price_fmt(contract, price):
        return ("{price:.%df}" % get_price_precision(contract)).format(price=price_from_wire(contract, price))

def quantity_fmt(contract, quantity):
        return ("{quantity:.%df}" % get_quantity_precision(contract)).format(quantity=quantity_from_wire(contract,
                                                                                                         quantity))

def dt_to_timestamp(dt):
    """Turns a datetime into a Sputnik timestamp (microseconds since epoch)

    :param dt:
    :type dt: datetime.datetime
    :returns: int
    """
    epoch = datetime.utcfromtimestamp(0)
    delta = dt - epoch
    timestamp = int(delta.total_seconds() * 1e6)
    return timestamp

def timestamp_to_dt(timestamp):
    """Turns a sputnik timestamp into a python datetime

    :param timestamp:
    :type timestamp: int
    :returns: datetime.datetime
    """
    return datetime.utcfromtimestamp(timestamp/1e6)

