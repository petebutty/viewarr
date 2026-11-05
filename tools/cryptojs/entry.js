import { x25519 } from '@noble/curves/ed25519';
import { chacha20poly1305 } from '@noble/ciphers/chacha';
import { hkdf } from '@noble/hashes/hkdf';
import { sha256 } from '@noble/hashes/sha256';
import { randomBytes } from '@noble/hashes/utils';

// Seals a UTF-8 string for the TV: ephemeral X25519 -> HKDF-SHA256(salt=token) -> ChaCha20-Poly1305.
window.viewarrSeal = function (tvPublicKeyB64, tokenB64, plaintext) {
  const b64 = (u) => btoa(String.fromCharCode(...u)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));
  const priv = randomBytes(32);
  const pub = x25519.getPublicKey(priv);
  const shared = x25519.getSharedSecret(priv, unb64(tvPublicKeyB64));
  const key = hkdf(sha256, shared, unb64(tokenB64), new TextEncoder().encode('viewarr-phone-setup-v1'), 32);
  const nonce = randomBytes(12);
  const ct = chacha20poly1305(key, nonce).encrypt(new TextEncoder().encode(plaintext));
  return JSON.stringify({ pub: b64(pub), nonce: b64(nonce), data: b64(ct) });
};
