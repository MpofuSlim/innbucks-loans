/**
 * InnBucks Loans — outbound request signing for channel gateways
 * (WhatsApp Node.js/Express orchestrator, React BFF, any server-side caller).
 *
 * Counterpart of the Java `ChannelSecurityFilter` / `HmacSignatureVerifier`.
 * Canonical string (MUST match the backend byte-for-byte):
 *
 *     channelId + "\n" + timestampMillis + "\n" + nonce + "\n" + sha256Hex(rawBody)
 *
 * Signature: HMAC-SHA256(secret, canonicalString), lowercase hex.
 *
 * Usage:
 *   const { signedHeaders } = require('./channel-signing');
 *   const body = JSON.stringify(loanApplication);           // sign EXACTLY what you send
 *   const headers = signedHeaders({
 *     channelId: process.env.CHANNEL_ID,                    // e.g. whatsapp_gateway_prod
 *     secret: process.env.CHANNEL_HMAC_SECRET,              // from vault — never hardcode
 *     body,
 *   });
 *   await fetch(`${CORE_API}/api/loans`, {
 *     method: 'POST',
 *     headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...headers },
 *     body,                                                  // the same string that was signed
 *   });
 *
 * Retry rule (idempotency): on timeout/5xx, retry with the SAME Idempotency-Key
 * but a FRESH nonce + timestamp + signature. The backend replays the cached
 * response for a completed key — funds cannot disburse twice.
 */
'use strict';

const crypto = require('crypto');

/** SHA-256 of the raw body, lowercase hex. Accepts string or Buffer. */
function sha256Hex(rawBody) {
  return crypto.createHash('sha256').update(rawBody ?? Buffer.alloc(0)).digest('hex');
}

/** Builds the canonical string the backend reconstructs and verifies. */
function canonicalString({ channelId, timestamp, nonce, body }) {
  return `${channelId}\n${timestamp}\n${nonce}\n${sha256Hex(body)}`;
}

/** HMAC-SHA256 signature over the canonical string, lowercase hex. */
function sign({ channelId, secret, timestamp, nonce, body }) {
  return crypto
    .createHmac('sha256', secret)
    .update(canonicalString({ channelId, timestamp, nonce, body }))
    .digest('hex');
}

/**
 * Produces the complete signed header set for one outbound call.
 * Every call gets a fresh nonce + timestamp; the Idempotency-Key identifies
 * the logical operation and is REUSED across retries of the same operation.
 */
function signedHeaders({ channelId, secret, body, idempotencyKey = crypto.randomUUID() }) {
  if (!channelId || !secret) {
    throw new Error('channelId and secret are required to sign a request');
  }
  const timestamp = Date.now().toString();          // epoch millis — ±5 min skew accepted
  const nonce = crypto.randomBytes(16).toString('hex'); // 128-bit single-use nonce

  return {
    'Idempotency-Key': idempotencyKey,
    'X-Channel-Id': channelId,
    'X-Channel-Timestamp': timestamp,
    'X-Channel-Nonce': nonce,
    'X-Channel-Signature': sign({ channelId, secret, timestamp, nonce, body }),
  };
}

module.exports = { sha256Hex, canonicalString, sign, signedHeaders };
