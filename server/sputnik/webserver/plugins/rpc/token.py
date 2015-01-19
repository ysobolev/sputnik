from sputnik import config
from sputnik import observatory

debug, log, warn, error, critical = observatory.get_loggers("rpc_token")

from sputnik.plugin import PluginException
from sputnik.webserver.plugin import ServicePlugin, authenticated, schema, error_handler
from sputnik.exception import WebserverException

from twisted.internet.defer import inlineCallbacks, returnValue, succeed
from autobahn import wamp
from autobahn.wamp.types import RegisterOptions
from datetime import datetime, timedelta
from sputnik import util


class TokenService(ServicePlugin):
    def __init__(self):
        ServicePlugin.__init__(self)

    def init(self):
        self.administrator = self.require("sputnik.webserver.plugins.backend.administrator.AdministratorProxy")
        self.cookie_jar = self.require("sputnik.webserver.plugins.authn.cookie.CookieLogin")
    
    @wamp.register(u"rpc.token.get_cookie")
    @error_handler
    @authenticated
    @schema(u"public/token.json#get_cookie")
    def get_cookie(self, username=None):
        cookie = self.cookie_jar.get_cookie(username)
        if cookie is None:
            cookie = self.cookie_jar.new_cookie(username)
        r = yield succeed(cookie)
        returnValue(r)

    @wamp.register(u"rpc.token.get_new_api_credentials")
    @error_handler
    @authenticated
    @schema(u"public/token.json#get_new_api_credentials")
    def get_new_api_credentials(self, expiration=None, username=None):
        if expiration is None:
            now = datetime.utcnow()
            expiration = util.dt_to_timestamp(now + timedelta(days=7))

        r = yield self.administrator.proxy.get_new_api_credentials(username, expiration)
        returnValue(r)

    @wamp.register(u"rpc.token.logout")
    @error_handler
    @authenticated
    @schema(u"public/token.json#logout")
    def logout(self, username):
        self.cookie_jar.delete_cookie(username)
        # TODO: disconnect here

        r = yield succeed(None)
        returnValue(r)

    @wamp.register(u"rpc.token.change_password")
    @error_handler
    @authenticated
    @schema(u"public/token.json#change_password")
    def change_password(self, old_password_hash, new_password_hash, username=None):
        """
        Changes a users password.  Leaves salt and two factor untouched.
        :param old_password_hash: current password
        :param new_password_hash: new password
        :returns: Deferred
        """


        result = yield self.administrator.proxy.reset_password_hash(username, old_password_hash, new_password_hash)
        returnValue(None)

    # @wamp.register(u"rpc.token.get_new_two_factor")
    # @schema(u"public/token.json#get_new_two_factor")
    # @authenticated
    # def get_new_two_factor(self, username=None):
    #     """prepares new two factor authentication for an account
    #
    #     :returns: str
    #     """
    #     #new = otp.base64.b32encode(os.urandom(10))
    #     #self.user.two_factor = new
    #     #return new
    #     raise NotImplementedError()
    #
    # @wamp.register(u"rpc.token.disable_two_factor")
    # @schema(u"public/token.json#disable_two_factor")
    # @authenticated
    # def disable_two_factor(self, confirmation, username=None):
    #     """
    #     disables two factor authentication for an account
    #     """
    #     #secret = self.session.query(models.User).filter_by(username=self.user.username).one().two_factor
    #     #logging.info('in disable, got secret: %s' % secret)
    #     #totp = otp.get_totp(secret)
    #     #if confirmation == totp:
    #     #    try:
    #     #        logging.info(self.user)
    #     #        self.user.two_factor = None
    #     #        logging.info('should be None till added user')
    #     #        logging.info(self.user.two_factor)
    #     #        self.session.add(self.user)
    #     #        logging.info('added user')
    #     #        self.session.commit()
    #     #        logging.info('commited')
    #     #        return True
    #     #    except:
    #     #        self.session.rollBack()
    #     #        return False
    #     raise NotImplementedError()
    #
    #
    # @wamp.register(u"rpc.token.register_two_factor")
    # @schema("public/token.json#register_two_factor")
    # @authenticated
    # def register_two_factor(self, confirmation):
    #     """
    #     registers two factor authentication for an account
    #     :param secret: secret to store
    #     :param confirmation: trial run of secret
    #     """
    #     # sanitize input
    #     #confirmation_schema = {"type": "number"}
    #     #validate(confirmation, confirmation_schema)
    #
    #     #there should be a db query here, or maybe we can just refernce self.user..
    #     #secret = 'JBSWY3DPEHPK3PXP' # = self.user.two_factor
    #
    #     #logging.info('two factor in register: %s' % self.user.two_factor)
    #     #secret = self.user.two_factor
    #     #test = otp.get_totp(secret)
    #     #logging.info(test)
    #
    #     #compare server totp to client side totp:
    #     #if confirmation == test:
    #     #    try:
    #     #        self.session.add(self.user)
    #     #        self.session.commit()
    #     #        return True
    #     #    except Exception as e:
    #     #        self.session.rollBack()
    #     #        return False
    #     #else:
    #     #    return False
    #     raise NotImplementedError()

    @inlineCallbacks
    def register(self, endpoint, procedure = None, options = None):
        results = yield ServicePlugin.register(self, endpoint, procedure, options=RegisterOptions(details_arg="details", discloseCaller=True))
        returnValue(results)
