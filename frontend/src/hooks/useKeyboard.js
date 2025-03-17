import { useState, useCallback } from 'react'
import { keyboardsApi } from '../api/keyboardsApi'

export function useKeyboard() {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const createKeyboard = useCallback(async (keyboardData) => {
    setLoading(true)
    setError(null)
    try {
      const result = await keyboardsApi.create(keyboardData)
      return result
    } catch (err) {
      setError(err.message)
      throw err
    } finally {
      setLoading(false)
    }
  }, [])

  const updateKeyboard = useCallback(async (id, keyboardData) => {
    setLoading(true)
    setError(null)
    try {
      const result = await keyboardsApi.update(id, keyboardData)
      return result
    } catch (err) {
      setError(err.message)
      throw err
    } finally {
      setLoading(false)
    }
  }, [])

  const deleteKeyboard = useCallback(async (id) => {
    setLoading(true)
    setError(null)
    try {
      await keyboardsApi.delete(id)
    } catch (err) {
      setError(err.message)
      throw err
    } finally {
      setLoading(false)
    }
  }, [])

  return {
    loading,
    error,
    createKeyboard,
    updateKeyboard,
    deleteKeyboard,
  }
} 