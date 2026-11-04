use std::sync::Arc;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection};
use crate::cert;
use crate::verifier::AndroidAutoServerVerifier;

pub fn create_tls_session() -> Result<ClientConnection, Box<dyn std::error::Error>> {
    let cert_der = CertificateDer::from_pem_slice(cert::CERTIFICATE.as_bytes())?;
    let key_der = PrivateKeyDer::from_pem_slice(cert::PRIVATE_KEY.as_bytes())?;

    let mut root_store = rustls::RootCertStore::empty();
    if let Ok(ca_der) = CertificateDer::from_pem_slice(cert::AAUTO_CERT.as_bytes()) {
        root_store.add(ca_der).ok();
    }

    let config = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AndroidAutoServerVerifier))
        .with_client_auth_cert(vec![cert_der], key_der)?;

    let server_name = ServerName::try_from("androidauto")?;
    let conn = ClientConnection::new(Arc::new(config), server_name)?;
    Ok(conn)
}
