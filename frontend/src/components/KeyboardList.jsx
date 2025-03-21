import React, { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Header, Card, Loader, Label } from 'semantic-ui-react'
import { useAuth0 } from '@auth0/auth0-react'
import ErrorDisplay from './ErrorDisplay'

function KeyboardList() {
  const navigate = useNavigate()
  const [keyboards, setKeyboards] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { isAuthenticated } = useAuth0()

  useEffect(() => {
    fetchKeyboards()
  }, [])

  const fetchKeyboards = async () => {
    try {
      const response = await fetch('http://localhost:8080/api/keyboards')
      if (!response.ok) {
        throw new Error('Failed to fetch keyboards')
      }
      const data = await response.json()
      setKeyboards(data)
      setError(null)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleAddKeyboard = () => {
    navigate('/keyboards/new')
  }

  const handleKeyboardClick = (id) => {
    navigate(`/keyboards/${id}`)
  }

  if (loading) {
    return (
      <div className="container" style={{ margin: '0 auto', maxWidth: '1200px', padding: '0 1rem' }}>
        <Loader active>Loading keyboards...</Loader>
      </div>
    )
  }

  return (
    <div className="container" style={{ margin: '0 auto', maxWidth: '1200px', padding: '0 1rem' }}>
      {!error && (
        <div style={{ 
          marginBottom: '2rem',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center'
        }}>
          <Header as='h2' content="Keyboard List" />
          {isAuthenticated && (
            <button 
              className="ui primary button"
              onClick={handleAddKeyboard}
            >
              Add Keyboard
            </button>
          )}
        </div>
      )}

      {error && (
        <ErrorDisplay 
          errorMessage={error} 
          onRetry={fetchKeyboards}
        />
      )}

      {!error && (
        <>
          <Card.Group>
            {keyboards.map((keyboard) => (
              <Card
                key={keyboard.id}
                onClick={() => handleKeyboardClick(keyboard.id)}
                style={{ cursor: 'pointer' }}
              >
                <Card.Content>
                  <Card.Header>{keyboard.name}</Card.Header>
                  <Card.Meta>
                    {keyboard.split && <Label color="blue">Split</Label>}
                  </Card.Meta>
                </Card.Content>
              </Card>
            ))}
          </Card.Group>

          {keyboards.length === 0 && (
            <div style={{ textAlign: 'center', marginTop: '2rem', color: '#666' }}>
              No keyboards found.
            </div>
          )}
        </>
      )}
    </div>
  )
}

export default KeyboardList 