use anyhow::{bail, Context, Result};
use serde::Deserialize;
use std::path::PathBuf;

#[derive(Debug, Deserialize)]
pub struct Config {
    pub db: DbConfig,
    #[serde(default)]
    pub appstore: AppStoreConfig,
    #[serde(default)]
    pub play: PlayConfig,
    #[serde(default)]
    pub github: GitHubConfig,
}

#[derive(Debug, Deserialize)]
pub struct DbConfig {
    pub path: String,
}

#[derive(Debug, Deserialize, Default)]
pub struct AppStoreConfig {
    #[serde(default)]
    pub enabled: bool,
    pub issuer_id: Option<String>,
    pub key_id: Option<String>,
    pub vendor_number: Option<String>,
    pub private_key_path: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub struct PlayConfig {
    #[serde(default)]
    pub enabled: bool,
    pub package: Option<String>,
    pub bucket: Option<String>,
    pub service_account_json_path: Option<String>,
}

#[derive(Debug, Deserialize, Default)]
pub struct GitHubConfig {
    #[serde(default)]
    pub enabled: bool,
    pub owner: Option<String>,
    pub repo: Option<String>,
    /// Env var name whose value is the token (not the token itself)
    pub token_env: Option<String>,
}

impl Config {
    pub fn load(path: &str) -> Result<Self> {
        let raw = std::fs::read_to_string(path)
            .with_context(|| format!("reading config file: {path}"))?;
        let mut cfg: Config = toml::from_str(&raw)
            .with_context(|| format!("parsing config file: {path}"))?;

        // Expand env-var references like ${VAR} in string fields.
        expand_env(&mut cfg.db.path);
        if let Some(p) = &mut cfg.appstore.private_key_path {
            expand_env(p);
        }
        if let Some(p) = &mut cfg.play.service_account_json_path {
            expand_env(p);
        }

        Ok(cfg)
    }

    pub fn db_path(&self) -> PathBuf {
        let p = self.db.path.replace('~', &std::env::var("HOME").unwrap_or_default());
        PathBuf::from(p)
    }
}

impl AppStoreConfig {
    pub fn validate(&self) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        let mut issues: Vec<&str> = Vec::new();
        if self.issuer_id.is_none() { issues.push("issuer_id"); }
        if self.key_id.is_none() { issues.push("key_id"); }
        if self.vendor_number.is_none() { issues.push("vendor_number"); }
        if self.private_key_path.is_none() { issues.push("private_key_path"); }
        if !issues.is_empty() {
            bail!("appstore config missing: {}", issues.join(", "));
        }
        let key_path = self.private_key_path.as_deref().unwrap();
        if !std::path::Path::new(key_path).exists() {
            bail!("appstore private_key_path not found: {key_path}");
        }
        Ok(())
    }
}

impl PlayConfig {
    pub fn validate(&self) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        if self.package.is_none() {
            bail!("play config missing: package");
        }
        if self.bucket.is_none() {
            bail!("play config missing: bucket");
        }
        let sa_path = self.service_account_json_path.as_deref().unwrap_or("");
        if sa_path.is_empty() {
            bail!("play config missing: service_account_json_path");
        }
        if !std::path::Path::new(sa_path).exists() {
            bail!("play service_account_json_path not found: {sa_path}");
        }
        Ok(())
    }
}

impl GitHubConfig {
    pub fn token(&self) -> Option<String> {
        self.token_env
            .as_deref()
            .and_then(|var| std::env::var(var).ok())
    }
}

fn expand_env(s: &mut String) {
    // Replace ${VAR_NAME} with the env var value.
    let mut result = s.clone();
    while let Some(start) = result.find("${") {
        if let Some(end) = result[start..].find('}') {
            let var_name = &result[start + 2..start + end];
            let value = std::env::var(var_name).unwrap_or_default();
            result = format!("{}{}{}", &result[..start], value, &result[start + end + 1..]);
        } else {
            break;
        }
    }
    *s = result;
}
