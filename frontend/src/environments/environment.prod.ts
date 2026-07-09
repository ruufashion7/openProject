export const environment = {
  production: true,
  googleMapsApiKey: 'YOUR_GOOGLE_MAPS_API_KEY', // Replace with your actual API key for production
  useJwtHttpOnlyCookie: false,
  /**
   * Direct API origin — browser calls Render without Vercel `/api` rewrite (avoids 502 gateway timeout on cold start).
   * Must match `frontend/vercel.json` rewrite host and `CORS_ALLOWED_ORIGINS` on Render.
   */
  apiBaseUrl: 'https://openproject-backend.onrender.com'
};
