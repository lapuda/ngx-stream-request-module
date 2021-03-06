package stm;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * Created by xpwu on 2016/11/28.
 */

public class Client{
  public interface Delegate {
    public void onPush(byte[] data);
  }

  public void pump() {
    if (net_ == null) {
      return;
    }
    net_.pump();
  }
  public interface AsyncEventHandler extends Net.AsyncEventHandler{}
  public void setAsyncEventHandler(AsyncEventHandler handler) {
    handler_ = handler;
    if (net_ != null) {
      net_.setAsyncEventHandler(handler_);
    }
  }

  public interface NetCallback {
    public void onSuccess();
    public void onFailed(String error);
  }
  public void setConnectHostAndPort(String host, int port, NetCallback callback){
    net_ = new Net(host, port);
    net_.setDelegate(new Net.Delegate() {
      public void onOpen(){
        Client.this.sendAllRequests();
        Client.this.netCallback_.onSuccess();
      }

      public void onClose(String reason){
        Client.this.errorAllRequests(reason);
        Client.this.netCallback_.onFailed(reason);
      }

      public void onMessage(byte[] data){
        Client.this.messageHandler_.handle(data);
      }
    });
    this.netCallback_ = callback;
    net_.setAsyncEventHandler(handler_);
    net_.setConfig(config_);
    net_.setTrustX509Certificate(ca_);
  }

  // unit: s  default: 30s; 4*60s; 10s
  public void setConfig(int connectTimeout, int heartbeatTime, int transmission) {
    config_.connectTimeout_ms = connectTimeout * 1000;
    config_.hearbeatTime_ms = heartbeatTime * 1000;
    config_.translatioin_ms = transmission * 1000;
    if (net_ != null) {
      net_.setConfig(config_);
    }
  }

  public void setTrustX509Certificate(X509Certificate ca) {
    ca_ = ca;
    if (net_ != null) {
      net_.setTrustX509Certificate(ca_);
    }
  }

  public interface RequestCallback {
    public void onSuccess(byte[] data);
    public void onFailed(String error);
    public void onComplete();
  }
  public void addRequest(byte[] body
    , Map<String, String>headers
    , RequestCallback callback){
    if (net_ == null) {
      callback.onComplete();
      callback.onFailed("host and port not set!");
      return;
    }
    if (handler_ == null) {
      callback.onComplete();
      callback.onFailed("async Event Handler not set!");
      return;
    }

    long id = reqID();
    Request request = new Request();
    request.body = body;
    request.headers = headers;
    request.requestCallback = callback;
    request.reqID = id;

    requests_.put(id, request);

    if (net_.status() == Net.Status.Open) {
      sendRequest(body, headers, id);
      return;
    }
    if (net_.status() == Net.Status.Connecting) {
      return;
    }
    connect();
  }

  public Client() {
    reqID_ = reqIDstart;
    net_ = null;
    protocol_ = new DefaultContentProtocol();
    requests_ = new HashMap<Long, Request>();
    ca_ = null;
    delegate_ = new Delegate() {
      @Override
      public void onPush(byte[] data) {
        // TODO debug log
      }
    };
    config_ = new Net.Config();
    config_.hearbeatTime_ms = 4*60*1000;
    config_.translatioin_ms = 10*1000;
    config_.connectTimeout_ms = 30*1000;
  }

  public void setDelegate(Delegate delegate) {
    this.delegate_ = delegate;
  }

  // ---- private class ----
  private class Request {
    long reqID;
    byte[] body;
    Map<String, String> headers;
    RequestCallback requestCallback;
  }

  private interface MessageHandler {
    public void handle(byte[] message);
  }

  private long reqID(){
    reqID_++;
    if (reqID_ < reqIDstart || reqID_ > Integer.MAX_VALUE) {
      reqID_ = reqIDstart;
    }
    return  reqID_;
  }

  private void connect(){
    normalMessageHandler_ = new MessageHandler() {
      @Override
      public void handle(byte[] message) {
        Response response = protocol_.parse(message);

        if (response.reqID == pushID) {
          Client.this.delegate_.onPush(response.data);
          return;
        }

        Request request = Client.this.requests_.get(response.reqID);
        if (request == null) {
//          System.out.println("not find request");
          return;
        }
        request.requestCallback.onComplete();
        if (response.status != Response.Status.Success) {
          if (response.data == null) {
            request.requestCallback.onFailed("may be server error, but server has closed the error log");
          } else {
            try {
              request.requestCallback.onFailed(new String(response.data, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
              request.requestCallback.onFailed("unkown error, because java utf-8 error");
            }
          }
        } else {
          request.requestCallback.onSuccess(response.data);
        }

        Client.this.requests_.remove(response.reqID);
      }
    };
    this.messageHandler_ = normalMessageHandler_;

    net_.open();
  }

  private void sendAllRequests(){
    for (Request request : requests_.values()) {
      sendRequest(request.body, request.headers, request.reqID);
    }
  }

  private void sendRequest(byte[] body, Map<String, String>headers, final long reqID){
    byte[] data = protocol_.build(body, headers, reqID);
    if (data == null) {
      net_.postTask(new Net.Task() {
        public void run() {
          Client.this.messageHandler_.handle(Client.this.protocol_.buildFailedMessage("build message error, maybe length of headers' key or value > 255, or is not asscii", reqID));
        }
      });
      return;
    }

    net_.send(data);
  }

  private void errorAllRequests(String error){ // async call
    ArrayList<Request> requests = new ArrayList<>(requests_.size());
    requests.addAll(requests_.values());
    for (Request request : requests) {
      normalMessageHandler_.handle(protocol_.buildFailedMessage(error, request.reqID));
    }
    try {
      requests_.clear();
    } catch (UnsupportedOperationException e) {
      requests_ = new HashMap<Long, Request>();
    }
  }

  private Net net_;
  private NetCallback netCallback_;
  private ContentProtocol protocol_;
  private Map<Long, Request> requests_;
  private long reqID_;
  private MessageHandler messageHandler_;
  private MessageHandler normalMessageHandler_;
  private Delegate delegate_;
  private AsyncEventHandler handler_;
  private Net.Config config_;
  private X509Certificate ca_;

  private static final long reqIDstart = 200;
  private static final long pushID = 1; // need equal server
}
