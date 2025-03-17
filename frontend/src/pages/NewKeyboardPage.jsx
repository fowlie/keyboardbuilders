import React from 'react'
import KeyboardForm from '../components/KeyboardForm'

function NewKeyboardPage() {
  const handleSubmit = async (formData) => {
    // TODO: Implement the API call to create a new keyboard
    console.log('Creating new keyboard:', formData)
  }

  return (
    <KeyboardForm onSubmit={handleSubmit} mode="new" />
  )
}

export default NewKeyboardPage 