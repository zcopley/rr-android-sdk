/* Copyright (c) 2009 Matthias Kaeppler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.richrelevance.internal.net.oauth.signpost;

import com.richrelevance.internal.net.oauth.signpost.basic.HttpURLConnectionRequestAdapter;
import com.richrelevance.internal.net.oauth.signpost.exception.OAuthCommunicationException;
import com.richrelevance.internal.net.oauth.signpost.exception.OAuthExpectationFailedException;
import com.richrelevance.internal.net.oauth.signpost.exception.OAuthMessageSignerException;
import com.richrelevance.internal.net.oauth.signpost.http.HttpParameters;
import com.richrelevance.internal.net.oauth.signpost.http.HttpRequest;
import com.richrelevance.internal.net.oauth.signpost.signature.AuthorizationHeaderSigningStrategy;
import com.richrelevance.internal.net.oauth.signpost.signature.HmacSha1MessageSigner;
import com.richrelevance.internal.net.oauth.signpost.signature.OAuthMessageSigner;
import com.richrelevance.internal.net.oauth.signpost.signature.SigningStrategy;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Random;

/**
 * ABC for consumer implementations. If you're developing a custom consumer you
 * will probably inherit from this class to save you a lot of work.
 *
 * @author Matthias Kaeppler
 */
public class OAuthConsumer {

    private String consumerKey, consumerSecret;

    private OAuthMessageSigner messageSigner;

    private SigningStrategy signingStrategy;

    // these are params that may be passed to the consumer directly (i.e.
    // without going through the request object)
    private HttpParameters additionalParameters;

    // these are the params which will be passed to the message signer
    private HttpParameters requestParameters;

    private boolean sendEmptyTokens;

    final private Random random = new Random(System.nanoTime());

    public OAuthConsumer(String consumerKey, String consumerSecret) {
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        setMessageSigner(new HmacSha1MessageSigner());
        setSigningStrategy(new AuthorizationHeaderSigningStrategy());
    }

    public void setMessageSigner(OAuthMessageSigner messageSigner) {
        this.messageSigner = messageSigner;
        messageSigner.setConsumerSecret(consumerSecret);
    }

    public void setSigningStrategy(SigningStrategy signingStrategy) {
        this.signingStrategy = signingStrategy;
    }

    protected HttpRequest wrap(HttpURLConnection connection) {
        return new HttpURLConnectionRequestAdapter(connection);
    }

    public String getTokenSecret() {
        return messageSigner.getTokenSecret();
    }

    public String getConsumerKey() {
        return this.consumerKey;
    }

    public String getConsumerSecret() {
        return this.consumerSecret;
    }

    public void sign(HttpURLConnection connection) throws OAuthCommunicationException,
            OAuthExpectationFailedException, OAuthMessageSignerException {
        sign(wrap(connection));
    }

    public synchronized HttpRequest sign(HttpRequest request) throws OAuthMessageSignerException,
            OAuthExpectationFailedException, OAuthCommunicationException {
        if (consumerKey == null) {
            throw new OAuthExpectationFailedException("consumer key not set");
        }
        if (consumerSecret == null) {
            throw new OAuthExpectationFailedException("consumer secret not set");
        }

        requestParameters = new HttpParameters();
        try {
            if (additionalParameters != null) {
                requestParameters.putAll(additionalParameters, false);
            }
            collectHeaderParameters(request, requestParameters);
            collectQueryParameters(request, requestParameters);
            collectBodyParameters(request, requestParameters);

            // add any OAuth params that haven't already been set
            completeOAuthParameters(requestParameters);

            requestParameters.remove(OAuth.OAUTH_SIGNATURE);

        } catch (IOException e) {
            throw new OAuthCommunicationException(e);
        }

        String signature = messageSigner.sign(request, requestParameters);

        signingStrategy.writeSignature(signature, request, requestParameters);

        return request;
    }

    /**
     * <p>
     * Helper method that adds any OAuth parameters to the given request
     * parameters which are missing from the current request but required for
     * signing. A good example is the oauth_nonce parameter, which is typically
     * not provided by the client in advance.
     * </p>
     * <p>
     * It's probably not a very good idea to override this method. If you want
     * to generate different nonces or timestamps, override
     * {@link #generateNonce()} or {@link #generateTimestamp()} instead.
     * </p>
     *
     * @param out the request parameter which should be completed
     */
    protected void completeOAuthParameters(HttpParameters out) {
        if (!out.containsKey(OAuth.OAUTH_CONSUMER_KEY)) {
            out.put(OAuth.OAUTH_CONSUMER_KEY, consumerKey, true);
        }
        if (!out.containsKey(OAuth.OAUTH_SIGNATURE_METHOD)) {
            out.put(OAuth.OAUTH_SIGNATURE_METHOD, messageSigner.getSignatureMethod(), true);
        }
        if (!out.containsKey(OAuth.OAUTH_TIMESTAMP)) {
            out.put(OAuth.OAUTH_TIMESTAMP, generateTimestamp(), true);
        }
        if (!out.containsKey(OAuth.OAUTH_NONCE)) {
            out.put(OAuth.OAUTH_NONCE, generateNonce(), true);
        }
        if (!out.containsKey(OAuth.OAUTH_VERSION)) {
            out.put(OAuth.OAUTH_VERSION, OAuth.VERSION_1_0, true);
        }
    }

    public HttpParameters getRequestParameters() {
        return requestParameters;
    }

    public void setSendEmptyTokens(boolean enable) {
        this.sendEmptyTokens = enable;
    }

    /**
     * Collects OAuth Authorization header parameters as per OAuth Core 1.0 spec
     * section 9.1.1
     *
     * @param request The request
     * @param out     The parameters
     */
    protected void collectHeaderParameters(HttpRequest request, HttpParameters out) {
        HttpParameters headerParams = OAuth.oauthHeaderToParamsMap(request.getHeader(OAuth.HTTP_AUTHORIZATION_HEADER));
        out.putAll(headerParams, false);
    }

    /**
     * Collects x-www-form-urlencoded body parameters as per OAuth Core 1.0 spec
     * section 9.1.1
     *
     * @param request The request
     * @param out     The parameters
     * @throws IOException If the stream fails
     */
    protected void collectBodyParameters(HttpRequest request, HttpParameters out)
            throws IOException {

        // collect x-www-form-urlencoded body params
        String contentType = request.getContentType();
        if (contentType != null && contentType.startsWith(OAuth.FORM_ENCODED)) {
            InputStream payload = request.getMessagePayload();
            out.putAll(OAuth.decodeForm(payload), true);
        }
    }

    /**
     * Collects HTTP GET query string parameters as per OAuth Core 1.0 spec
     * section 9.1.1
     *
     * @param request The request
     * @param out     The parameters
     */
    protected void collectQueryParameters(HttpRequest request, HttpParameters out) {

        String url = request.getRequestUrl();
        int q = url.indexOf('?');
        if (q >= 0) {
            // Combine the URL query string with the other parameters:
            out.putAll(OAuth.decodeForm(url.substring(q + 1)), true);
        }
    }

    protected String generateTimestamp() {
        return Long.toString(System.currentTimeMillis() / 1000L);
    }

    protected String generateNonce() {
        return Long.toString(random.nextLong());
    }
}
