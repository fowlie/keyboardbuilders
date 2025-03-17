import { React } from 'react'
import { createRoot } from 'react-dom/client'
import { Auth0Provider } from '@auth0/auth0-react';
import App from './App.jsx'
import 'semantic-ui-css/semantic.min.css'

const root = createRoot(document.getElementById('root'));

root.render(
  <Auth0Provider
    domain="dev-keyboardbuilders.eu.auth0.com"
    clientId="Acan7xXV0oeyXHcjLgbHAE5U81m0xKn5"
    authorizationParams={{
      redirect_uri: window.location.origin
    }}
  >
    <App />
  </Auth0Provider>,
);