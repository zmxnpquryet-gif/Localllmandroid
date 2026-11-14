//! OpenAI-compatible image generation adapter used by the All-in-One edition.

use crate::contracts::{ImageGenerationBackend, ImageRequest, ImageResult};
use base64::Engine;
use reqwest::blocking::Client;
use serde_json::{json, Value};
use std::time::Duration;

pub struct OpenAiImageBackend {
    base_url: String,
    api_key: Option<String>,
    client: Client,
}

impl OpenAiImageBackend {
    pub fn new(base_url: impl Into<String>, api_key: Option<String>) -> Self {
        Self {
            base_url: base_url.into().trim().trim_end_matches('/').to_string(),
            api_key: api_key.filter(|key| !key.trim().is_empty()),
            client: Client::builder()
                .connect_timeout(Duration::from_secs(10))
                .build()
                .expect("the HTTP client configuration is valid"),
        }
    }

    fn endpoint(&self) -> String {
        if self.base_url.ends_with("/v1") {
            format!("{}/images/generations", self.base_url)
        } else {
            format!("{}/v1/images/generations", self.base_url)
        }
    }
}

impl ImageGenerationBackend for OpenAiImageBackend {
    fn available(&self) -> bool {
        !self.base_url.is_empty()
    }

    fn generate(&self, request: &ImageRequest) -> Result<ImageResult, String> {
        if !self.available() {
            return Err("Image endpoint is not configured".into());
        }
        let body = json!({
            "model": request.model,
            "prompt": request.prompt,
            "size": request.size,
            "response_format": "b64_json",
        });
        let mut builder = self
            .client
            .post(self.endpoint())
            .timeout(Duration::from_secs(300))
            .header("Content-Type", "application/json")
            .body(body.to_string());
        if let Some(key) = &self.api_key {
            builder = builder.header("Authorization", format!("Bearer {key}"));
        }
        let response = builder
            .send()
            .map_err(|error| format!("Image generation failed: {error}"))?;
        let status = response.status();
        let payload = response
            .text()
            .map_err(|error| format!("Image response could not be read: {error}"))?;
        if !status.is_success() {
            return Err(format!("Image API HTTP {status}: {payload}"));
        }
        let value: Value = serde_json::from_str(&payload)
            .map_err(|error| format!("Image response is not JSON: {error}"))?;
        if let Some(encoded) = value
            .get("data")
            .and_then(Value::as_array)
            .and_then(|items| items.first())
            .and_then(|item| item.get("b64_json"))
            .and_then(Value::as_str)
            .filter(|encoded| !encoded.is_empty())
        {
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(encoded)
                .map_err(|error| format!("Image base64 was invalid: {error}"))?;
            return Ok(ImageResult {
                bytes,
                media_type: "image/png".into(),
            });
        }
        if let Some(url) = value
            .get("data")
            .and_then(Value::as_array)
            .and_then(|items| items.first())
            .and_then(|item| item.get("url"))
            .and_then(Value::as_str)
            .filter(|url| !url.is_empty())
        {
            let response = self
                .client
                .get(url)
                .timeout(Duration::from_secs(120))
                .send()
                .map_err(|error| format!("Image download failed: {error}"))?;
            let status = response.status();
            if !status.is_success() {
                return Err(format!("Image download HTTP {status}"));
            }
            let media_type = response
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|value| value.to_str().ok())
                .unwrap_or("image/png")
                .to_string();
            let bytes = response
                .bytes()
                .map_err(|error| format!("Image download could not be read: {error}"))?
                .to_vec();
            return Ok(ImageResult { bytes, media_type });
        }
        Err("Image response contained neither b64_json nor url".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transport::test_support;

    #[test]
    fn decodes_base64_image_response() {
        let png = [0x89, b'P', b'N', b'G'];
        let encoded = base64::engine::general_purpose::STANDARD.encode(png);
        let body = Box::leak(format!("{{\"data\":[{{\"b64_json\":\"{encoded}\"}}]}}").into_boxed_str());
        let server = test_support::serve_once(body, "application/json");
        let backend = OpenAiImageBackend::new(server.base_url, None);
        let result = backend
            .generate(&ImageRequest {
                model: "image-model".into(),
                prompt: "a tree".into(),
                size: "512x512".into(),
                output_format: "png".into(),
            })
            .expect("image response decodes");
        assert_eq!(result.bytes, png);
    }
}
