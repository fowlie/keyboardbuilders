import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import KeyboardForm from '../components/KeyboardForm'
import { keyboardsApi } from '../api/keyboardsApi'
import ErrorDisplay from '../components/ErrorDisplay'

function NewKeyboardPage() {
  const [error, setError] = useState(null);
  const navigate = useNavigate();
  
  const handleSubmit = async (formData) => {
    try {
      setError(null);
      await keyboardsApi.create(formData);
      navigate('/keyboards');
    } catch (error) {
      console.error('Failed to create keyboard:', error);
      setError(error.message);
    }
  }

  const handleRetry = () => {
    setError(null);
  };

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