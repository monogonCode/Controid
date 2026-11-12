use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{ED25519, Ed25519KeyPair, KeyPair, UnparsedPublicKey};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;
use thiserror::Error;

#[derive(Debug, Error)]
pub enum AuthError {
    #[error("key generation failed")]
    KeyGen,
    #[error("invalid key material")]
    InvalidKey,
    #[error("signature verification failed")]
    VerificationFailed,
    #[error("random generation failed")]
    RngFailed,
    #[error("key i/o error: {0}")]
    Io(#[from] std::io::Error),
}

pub struct IdentityKeyPair {
    inner: Ed25519KeyPair,
}

impl std::fmt::Debug for IdentityKeyPair {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("IdentityKeyPair")
            .field("public_key", &self.public_key_bytes())
            .finish()
    }
}

impl IdentityKeyPair {
    pub fn generate_pkcs8() -> Result<Vec<u8>, AuthError> {
        let rng = SystemRandom::new();
        let doc = Ed25519KeyPair::generate_pkcs8(&rng).map_err(|_| AuthError::KeyGen)?;
        Ok(doc.as_ref().to_vec())
    }

    pub fn generate() -> Result<(Self, Vec<u8>), AuthError> {
        let pkcs8 = Self::generate_pkcs8()?;
        let pair = Self::from_pkcs8(&pkcs8)?;
        Ok((pair, pkcs8))
    }

    pub fn from_pkcs8(pkcs8: &[u8]) -> Result<Self, AuthError> {
        let inner = Ed25519KeyPair::from_pkcs8(pkcs8).map_err(|_| AuthError::InvalidKey)?;
        Ok(Self { inner })
    }

    pub fn save_pkcs8(path: &Path, pkcs8: &[u8]) -> Result<(), AuthError> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }

        let mut opts = OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }
        let mut file = opts.open(path)?;
        file.write_all(pkcs8)?;
        file.sync_all()?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }
        Ok(())
    }

    pub fn load_pkcs8(path: &Path) -> Result<Self, AuthError> {
        let bytes = fs::read(path)?;
        Self::from_pkcs8(&bytes)
    }

    pub fn load_or_generate(path: &Path) -> Result<Self, AuthError> {
        if path.exists() {
            Self::load_pkcs8(path)
        } else {
            let (pair, pkcs8) = Self::generate()?;
            Self::save_pkcs8(path, &pkcs8)?;
            Ok(pair)
        }
    }

    pub fn public_key_bytes(&self) -> [u8; 32] {
        let mut out = [0u8; 32];
        out.copy_from_slice(self.inner.public_key().as_ref());
        out
    }

    pub fn sign(&self, msg: &[u8]) -> [u8; 64] {
        let sig = self.inner.sign(msg);
        let mut out = [0u8; 64];
        out.copy_from_slice(sig.as_ref());
        out
    }

    pub fn verify_ed25519(
        pubkey: &[u8; 32],
        message: &[u8],
        signature: &[u8; 64],
    ) -> Result<(), AuthError> {
        let verifier = UnparsedPublicKey::new(&ED25519, pubkey);
        verifier
            .verify(message, signature)
            .map_err(|_| AuthError::VerificationFailed)
    }
}

pub fn random_nonce_32() -> Result<[u8; 32], AuthError> {
    let mut nonce = [0u8; 32];
    SystemRandom::new()
        .fill(&mut nonce)
        .map_err(|_| AuthError::RngFailed)?;
    Ok(nonce)
}

pub fn random_nonce_16() -> Result<[u8; 16], AuthError> {
    let mut nonce = [0u8; 16];
    SystemRandom::new()
        .fill(&mut nonce)
        .map_err(|_| AuthError::RngFailed)?;
    Ok(nonce)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn ed25519_keygen_sign_verify_and_tamper_detection() {
        let (keypair, pkcs8) = IdentityKeyPair::generate().unwrap();
        let reloaded = IdentityKeyPair::from_pkcs8(&pkcs8).unwrap();
        assert_eq!(keypair.public_key_bytes(), reloaded.public_key_bytes());

        let nonce = random_nonce_32().unwrap();
        let sig = reloaded.sign(&nonce);
        let pubkey = keypair.public_key_bytes();

        IdentityKeyPair::verify_ed25519(&pubkey, &nonce, &sig).unwrap();

        let mut bad_msg = nonce;
        bad_msg[0] ^= 0xFF;
        assert!(matches!(
            IdentityKeyPair::verify_ed25519(&pubkey, &bad_msg, &sig),
            Err(AuthError::VerificationFailed)
        ));

        let mut bad_sig = sig;
        bad_sig[63] ^= 0x01;
        assert!(matches!(
            IdentityKeyPair::verify_ed25519(&pubkey, &nonce, &bad_sig),
            Err(AuthError::VerificationFailed)
        ));

        let mut bad_key = pubkey;
        bad_key[0] ^= 0x01;
        assert!(matches!(
            IdentityKeyPair::verify_ed25519(&bad_key, &nonce, &sig),
            Err(AuthError::VerificationFailed)
        ));
    }

    #[test]
    fn pkcs8_load_or_generate_persists_with_0600() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("controid-auth-test-{unique}"));
        let key_path = dir.join("identity.pk8");

        let first = IdentityKeyPair::load_or_generate(&key_path).unwrap();
        let second = IdentityKeyPair::load_or_generate(&key_path).unwrap();
        assert_eq!(first.public_key_bytes(), second.public_key_bytes());

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(&key_path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn random_nonces_are_distinct() {
        let n1 = random_nonce_32().unwrap();
        let n2 = random_nonce_32().unwrap();
        assert_ne!(n1, n2);

        let s1 = random_nonce_16().unwrap();
        let s2 = random_nonce_16().unwrap();
        assert_ne!(s1, s2);
    }

    #[test]
    fn invalid_pkcs8_rejected() {
        assert!(matches!(
            IdentityKeyPair::from_pkcs8(&[1, 2, 3]),
            Err(AuthError::InvalidKey)
        ));
    }

    #[test]
    fn rfc8032_test_vectors_verify() {
        // RFC 8032 Section 7.1 TEST 1 (empty message)
        let pk1: [u8; 32] = [
            0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64,
            0x07, 0x3a, 0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68,
            0xf7, 0x07, 0x51, 0x1a,
        ];
        let sig1: [u8; 64] = [
            0xe5, 0x56, 0x43, 0x00, 0xc3, 0x60, 0xac, 0x72, 0x90, 0x86, 0xe2, 0xcc, 0x80, 0x6e,
            0x82, 0x8a, 0x84, 0x87, 0x7f, 0x1e, 0xb8, 0xe5, 0xd9, 0x74, 0xd8, 0x73, 0xe0, 0x65,
            0x22, 0x49, 0x01, 0x55, 0x5f, 0xb8, 0x82, 0x15, 0x90, 0xa3, 0x3b, 0xac, 0xc6, 0x1e,
            0x39, 0x70, 0x1c, 0xf9, 0xb4, 0x6b, 0xd2, 0x5b, 0xf5, 0xf0, 0x59, 0x5b, 0xbe, 0x24,
            0x65, 0x51, 0x41, 0x43, 0x8e, 0x7a, 0x10, 0x0b,
        ];
        IdentityKeyPair::verify_ed25519(&pk1, b"", &sig1).unwrap();

        // RFC 8032 Section 7.1 TEST 3 (2-byte message 0xaf, 0x82)
        let pk3: [u8; 32] = [
            0xfc, 0x51, 0xcd, 0x8e, 0x62, 0x18, 0xa1, 0xa3, 0x8d, 0xa4, 0x7e, 0xd0, 0x02, 0x30,
            0xf0, 0x58, 0x08, 0x16, 0xed, 0x13, 0xba, 0x33, 0x03, 0xac, 0x5d, 0xeb, 0x91, 0x15,
            0x48, 0x90, 0x80, 0x25,
        ];
        let sig3: [u8; 64] = [
            0x62, 0x91, 0xd6, 0x57, 0xde, 0xec, 0x24, 0x02, 0x48, 0x27, 0xe6, 0x9c, 0x3a, 0xbe,
            0x01, 0xa3, 0x0c, 0xe5, 0x48, 0xa2, 0x84, 0x74, 0x3a, 0x44, 0x5e, 0x36, 0x80, 0xd7,
            0xdb, 0x5a, 0xc3, 0xac, 0x18, 0xff, 0x9b, 0x53, 0x8d, 0x16, 0xf2, 0x90, 0xae, 0x67,
            0xf7, 0x60, 0x98, 0x4d, 0xc6, 0x59, 0x4a, 0x7c, 0x15, 0xe9, 0x71, 0x6e, 0xd2, 0x8d,
            0xc0, 0x27, 0xbe, 0xce, 0xea, 0x1e, 0xc4, 0x0a,
        ];
        IdentityKeyPair::verify_ed25519(&pk3, &[0xaf, 0x82], &sig3).unwrap();
    }
}
