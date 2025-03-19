import { React } from 'react'
import { createRoot } from 'react-dom/client'
import { Auth0Provider } from '@auth0/auth0-react';
import App from './App.jsx'
import './index.css'
import 'semantic-ui-css/semantic.min.css'

const root = createRoot(document.getElementById('root'));

root.render(
  <Auth0Provider
    domain="keyboardbuilders.eu.auth0.com"
    clientId="yJarVAikY1ak0vDbNwxQAiO6Dc0tOpCt"
    authorizationParams={{
      redirect_uri: window.location.origin
    }}
  >
    <App />
  </Auth0Provider>,
);