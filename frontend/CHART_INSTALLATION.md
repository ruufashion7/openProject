# Chart.js Installation Instructions

The Sales Visualization page requires Chart.js and ng2-charts packages to be installed.

## Installation Steps

1. Navigate to the frontend directory:
   ```bash
   cd frontend
   ```

2. Install the required packages:
   ```bash
   npm install chart.js ng2-charts
   ```

   Or if you prefer to install specific versions:
   ```bash
   npm install chart.js@^4.4.0 ng2-charts@^5.0.0
   ```

3. After installation, the temporary type declarations in `src/types/` can be removed as the actual packages will provide their own types.

## Verification

After installation, verify the packages are installed:
```bash
npm list chart.js ng2-charts
```

## Troubleshooting

If you encounter permission errors during installation:
- Try running with `sudo` (not recommended for production)
- Check npm permissions: `npm config get prefix`
- Use a node version manager like `nvm` or `volta`

If TypeScript still shows errors after installation:
- Restart your IDE/editor
- Run `npm run build` to verify compilation
- Check that `node_modules/chart.js` and `node_modules/ng2-charts` exist

## Note

Temporary type declaration files have been created in `src/types/` to allow the code to compile before the packages are installed. These will be automatically ignored once the real packages are installed.

