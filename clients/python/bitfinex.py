# Copyright (c) 2014, 2015 Mimetic Markets, Inc.
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are
# met:
#
# 1. Redistributions of source code must retain the above copyright
# notice, this list of conditions and the following disclaimer.
#
# 2. Redistributions in binary form must reproduce the above copyright
# notice, this list of conditions and the following disclaimer in the
# documentation and/or other materials provided with the distribution.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
# IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
# TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
# PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
# HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
# SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
# TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
# PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
# LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
# NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
# SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
#

__author__ = 'sameer'

import hmac
import hashlib
import json
import time
import base64
from decimal import Decimal
from pprint import pprint
from urllib import urlencode

import treq
from twisted.internet.defer import inlineCallbacks, returnValue
from twisted.internet import reactor
from twisted.python import log

import util


class BitFinexException(Exception):
    pass

class BitFinex():
    def __init__(self, api_key, api_secret, endpoint="https://api.bitfinex.com"):
        self.api_key = api_key
        self.api_secret = api_secret
        self.endpoint = endpoint

    def get_auth(self, call, params):
        nonce = str(long(time.time() * 1e6))

        if params:
            request = call + "?" + urlencode(params)
        else:
            request = call

        p = params.copy()
        p.update({'nonce': nonce,
             'request': request})

        payload_json = json.dumps(p)
        payload = base64.b64encode(payload_json.encode('utf-8'))
        signature = hmac.new(self.api_secret, payload, hashlib.sha384).hexdigest()
        headers = {'x-bfx-apikey': self.api_key,
                   'x-bfx-payload': payload,
                   'x-bfx-signature': signature}
        return headers

    @inlineCallbacks
    def get(self, call, params={}, auth=False):
        url = self.endpoint + call
        if auth:
            headers = self.get_auth(call, params)
        else:
            headers = None

        response = yield treq.get(url, params=params, headers=headers)


        content = yield response.content()
        result = json.loads(content)

        if 'message' in result:
            raise BitFinexException(result['message'])

        returnValue(result)

    def ticker_to_symbol(self, ticker):
        symbol = ticker.lower().replace('/', '')
        return symbol

    def symbol_to_ticker(self, symbol):
        upper = symbol.upper()
        ticker = upper[:3] + "/" + upper[4:]
        return ticker

    @inlineCallbacks
    def getOrderBook(self, ticker):
        symbol = self.ticker_to_symbol(ticker)
        call = "/v1/book/%s" % symbol
        result = yield self.get(call)
        book = {'contract': ticker,
                'bids': [{'price': Decimal(bid['price']), 'quantity': Decimal(bid['amount'])} for bid in result['bids']],
                'asks': [{'price': Decimal(ask['price']), 'quantity': Decimal(ask['amount'])} for ask in result['asks']],
                'timestamp': None}
        returnValue(book)

    @inlineCallbacks
    def getNewAddress(self, ticker):
        if ticker == "BTC":
            call = "/v1/deposit/new"
            data = {'currency': ticker,
                    'method': 'bitcoin',
                    'wallet_name': 'trading'}
            result = yield self.get(call, params=data, auth=True)
            if result['result'] != 'success':
                raise Exception(result['address'])
            else:
                returnValue(result['address'])
        else:
            raise NotImplementedError

    def getCurrentAddress(self, ticker):
        return self.getNewAddress(ticker)

    def requestWithdrawal(self, ticker):
        raise NotImplementedError

    @inlineCallbacks
    def placeOrder(self, ticker, quantity, price, side):
        symbol = self.ticker_to_symbol(ticker)
        call = "/v1/order/new"
        data = {'symbol': symbol,
                'amount': str(quantity),
                'price': str(price),
                'side': side.lower(),
                'type': 'limit'}
        result = yield self.post(call, data=data)
        returnValue(result['order_id'])

    @inlineCallbacks
    def cancelOrder(self, order_id):
        call = "/v1/order/cancel"
        data = {'order_id': order_id}
        result = yield self.post(call, data=data)
        returnValue(result)

    @inlineCallbacks
    def getOpenOrders(self):
        call = "/v1/orders"
        result = yield self.post(call)
        orders = {order['order_id']: {'contract': self.symbol_to_ticker(order['symbol']),
                       'price': Decimal(order['price']),
                       'timestamp': int(order['timestamp'] * 1e6),
                       'quantity': Decimal(order['original_amount']),
                       'quantity_left': Decimal(order['remaining_amount']),
                       'is_cancelled': order['is_cancelled'],
                       'id': order['order_id']}
        for order in result}
        returnValue(orders)

    @inlineCallbacks
    def getPositions(self):
        call = "/v1/balances"
        result = yield self.get(call, auth=True)
        positions = {balance['currency']: {'position': Decimal(balance['amount'])} for balance in result}
        returnValue(positions)

        # We need this call for swaps and stuff?
        # call = "/v1/positions"
        # result = yield self.post(call)
        # positions = {}
        # raise NotImplementedError

    @inlineCallbacks
    def getTransactionHistory(self, start_datetime, end_datetime):
        start_timestamp = int(util.dt_to_timestamp(start_datetime)/1e6)
        end_timestamp = int(util.dt_to_timestamp(end_datetime)/1e6)
        call = "/v1/history"
        result = []
        for currency in ['USD', 'BTC']:
            data = {'currency': currency,
                    'since': start_timestamp,
                    'until': end_timestamp}
            result += yield self.post(call, data=data)
        transactions = [{'timestamp': int(transaction['timestamp'] * 1e6),
                         'contract': transaction['currency'],
                         'quantity': abs(Decimal(transaction['amount'])),
                         'direction': 'credit' if transaction['amount'] > 0 else 'debit',
                         'balance': Decimal(transaction['balance']),
                         'note': transaction['description']}
                        for transaction in result]
        returnValue(transactions)






if __name__ == "__main__":
    bitfinex = BitFinex()
    #bitfinex.getOrderBook('BTC/USD').addCallback(pprint).addErrback(log.err)
    #bitfinex.getPositions().addCallback(pprint).addErrback(log.err)
    bitfinex.getNewAddress('BTC').addCallback(pprint).addErrback(log.err)

    reactor.run()