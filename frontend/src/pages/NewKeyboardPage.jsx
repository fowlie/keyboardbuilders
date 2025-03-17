import React from 'react'
import KeyboardForm from '../components/KeyboardForm'
import { keyboardsApi } from '../api/keyboardsApi'

function NewKeyboardPage() {
  const handleSubmit = async (formData) => {
    try {
      await keyboardsApi.create(formData)
    } catch (error) {
      console.error('Failed to create keyboard:', error)
      // TODO: Add proper error handling/display
    }
  }

  return (
    <KeyboardForm onSubmit={handleSubmit} mode="new" />
  )
}

export default NewKeyboardPage 