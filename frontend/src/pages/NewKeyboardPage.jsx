import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import KeyboardForm from '../components/KeyboardForm'
import { keyboardsApi } from '../api/keyboardsApi'
import ErrorDisplay from '../components/ErrorDisplay'
import LoginButton from '../components/LoginButton'

function NewKeyboardPage() {
  const [error, setError] = useState(null);
  const navigate = useNavigate();
  const { getAccessTokenSilently, isAuthenticated, isLoading } = useAuth0();
  
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

  if (isLoading) {
    return <div>Loading...</div>;
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