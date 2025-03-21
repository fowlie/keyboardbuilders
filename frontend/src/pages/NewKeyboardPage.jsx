import React, { useState, useContext } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import { Loader, Message } from 'semantic-ui-react'
import KeyboardForm from '../components/KeyboardForm'
import { keyboardsApi } from '../api/keyboardsApi'
import ErrorDisplay from '../components/ErrorDisplay'
import LoginButton from '../components/LoginButton'
import { UserContext } from '../App'

function NewKeyboardPage() {
  const [error, setError] = useState(null);
  const navigate = useNavigate();
  const { getAccessTokenSilently, isAuthenticated, isLoading } = useAuth0();
  const { userRegistered, userLoading, userError } = useContext(UserContext);
  
  const handleSubmit = async (formData) => {
    try {
      setError(null);
      console.log('Creating keyboard with token');
      await keyboardsApi.create(formData, getAccessTokenSilently);
      navigate('/keyboards');
    } catch (error) {
      console.error('Failed to create keyboard:', error);
      setError(error.message);
    }
  }

  const handleRetry = () => {
    setError(null);
  };

  if (isLoading || userLoading) {
    return <Loader active inline='centered'>Loading...</Loader>;
  }

  if (!isAuthenticated) {
    return (
      <div style={{ textAlign: 'center', marginTop: '2rem' }}>
        <h2>Authentication Required</h2>
        <p>You need to be logged in to create a keyboard.</p>
        <LoginButton />
      </div>
    );
  }

  if (!userRegistered) {
    return (
      <div style={{ textAlign: 'center', marginTop: '2rem' }}>
        <Message warning>
          <Message.Header>User Registration Required</Message.Header>
          <p>We're having trouble registering your profile in our system.</p>
          {userError && <p>Error: {userError}</p>}
          <p>Please try refreshing the page or log out and log back in.</p>
        </Message>
      </div>
    );
  }

  return (
    <>
      {error ? (
        <ErrorDisplay 
          errorMessage={error} 
          onRetry={handleRetry}
        />
      ) : (
        <KeyboardForm onSubmit={handleSubmit} mode="new" />
      )}
    </>
  )
}

export default NewKeyboardPage 