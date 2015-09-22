#
# Copyright 2014 Mimetic Markets, Inc.
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#

__author__ = 'sameer'

from sqlalchemy.orm.exc import NoResultFound
from sqlalchemy import func
from twisted.python import log
from decimal import Decimal
import sputnik.database.models as models
from sputnik.util.conversions import timestamp_to_dt

def get_cash_spent(contract, price, quantity):
    if contract.contract_type == "futures" or contract.contract_type == "prediction":
        cash_float = Decimal(quantity * price * contract.lot_size) / contract.denominator
    else:
        payout_contract = contract.payout_contract
        cash_float = Decimal(quantity * price) / (contract.denominator * payout_contract.denominator)

    cash_int = int(cash_float)
    if cash_float != cash_int:
        message = "cash_spent (%f) is not an integer: (quantity=%d price=%d contract.lot_size=%d contract.denominator=%d" % \
                  (cash_float, quantity, price, contract.lot_size, contract.denominator)
        log.err(message)

    return cash_int

def get_fees(user, contract, price, quantity, trial_period=False, ap=None):
    """
    Given a transaction, figure out how much fees need to be paid in what currencies
    :param username:
    :type username: str
    :param contract:
    :type contract: Contract
    :param transaction_size:
    :type transaction_size: int
    :returns: dict
    """

    # No fees during trial period
    if trial_period:
        return {}

    # TODO: Make fees based on transaction size
    transaction_size = get_cash_spent(contract, price, quantity)
    base_fee = transaction_size * contract.fees
    # If we don't know the aggressive/passive -- probably because we're
    # checking what the fees might be before placing an order
    # so we assume the fees are the max possible
    if ap is None:
        user_factor = max(user.fees.aggressive_factor, user.fees.passive_factor)
    elif ap == "aggressive":
        user_factor = user.fees.aggressive_factor
    else:
        user_factor = user.fees.passive_factor

    # 100 because factors are in % and 10000 because fees are in bps
    final_fee = int(round(base_fee * user_factor / 100 / 10000))
    return {contract.denominated_contract.ticker: final_fee}

def get_deposit_fees(user, contract, deposit_amount, trial_period=False):
    if trial_period:
        return {}

    base_fee = contract.deposit_base_fee + Decimal(deposit_amount * contract.deposit_bps_fee) / 10000
    user_factor = Decimal(user.fees.deposit_factor) / 100
    final_fee = int(round(base_fee * user_factor))

    return {contract.ticker: final_fee}

def get_withdraw_fees(user, contract, withdraw_amount, trial_period=False):
    if trial_period:
        return {}

    base_fee = contract.withdraw_base_fee + Decimal(withdraw_amount * contract.withdraw_bps_fee) / 10000
    user_factor = Decimal(user.fees.withdraw_factor) / 100
    final_fee = int(round(base_fee * user_factor))

    return {contract.ticker: final_fee}


def get_contract(session, ticker):
    """
    Return the Contract object corresponding to the ticker.
    :param session: the sqlalchemy session to use
    :param ticker: the ticker to look up or a Contract id
    :type ticker: str, models.Contract
    :returns: models.Contract -- the Contract object matching the ticker
    :raises: AccountantException
    """

    # TODO: memoize this!

    if isinstance(ticker, models.Contract):
        return ticker

    try:
        ticker = int(ticker)
        return session.query(models.Contract).filter_by(
            id=ticker).one()
    except NoResultFound:
        raise Exception("Could not resolve contract '%s'." % ticker)
    except ValueError:
        # drop through
        pass

    try:
        return session.query(models.Contract).filter_by(
            ticker=ticker).order_by(models.Contract.id.desc()).first()
    except NoResultFound:
        raise Exception("Could not resolve contract '%s'." % ticker)

def position_calculated(position, session, checkpoint=None, start=None, end=None):
    if start is None:
        start = position.position_cp_timestamp or timestamp_to_dt(0)
    if checkpoint is None:
        checkpoint = position.position_checkpoint or 0

    rows = session.query(func.sum(models.Posting.quantity).label('quantity_sum'),
                         func.max(models.Journal.timestamp).label('last_timestamp')).filter_by(
        username=position.username).filter_by(
        contract_id=position.contract_id).filter(
        models.Journal.id==models.Posting.journal_id).filter(
        models.Journal.timestamp > start)
    if end is not None:
        rows = rows.filter(models.Journal.timestamp <= end)

    try:
        grouped = rows.group_by(models.Posting.username).one()
        calculated = grouped.quantity_sum
        last_posting_timestamp = grouped.last_timestamp
    except NoResultFound:
        calculated = 0
        last_posting_timestamp = start


    return int(checkpoint + calculated), last_posting_timestamp

