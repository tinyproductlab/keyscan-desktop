# Security Policy

## Supported Versions

Security fixes are handled for the latest public source version of KeyScan.

## Reporting a Vulnerability

Please report security issues privately by email:

```text
keyscan.feedback@zohomail.com
```

Please include:

- A short description of the issue
- Steps to reproduce
- Affected version or commit
- Any relevant screenshots, logs, or proof of concept

Do not publish exploit details publicly before the issue has been reviewed.

## Sensitive Files

Never upload or share:

- `release/keyscan-release.jks`
- `release/keystore.properties`
- WebDAV accounts or passwords
- Private backup files
- Personal test data

The repository `.gitignore` is configured to exclude release signing secrets and build outputs.
