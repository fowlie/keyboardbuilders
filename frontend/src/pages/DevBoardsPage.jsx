import { useState, useEffect, useContext } from 'react'
import { Container, Header, Button, Grid, Loader, Message } from 'semantic-ui-react'
import { Link } from 'react-router-dom'
import { useAuth0 } from '@auth0/auth0-react'
import { UserContext } from '../App'
import DevBoardCard from '../components/DevBoardCard.jsx'
import { devBoardsApi } from '../api/devBoardsApi'

function DevBoardsPage() {
  const [devBoards, setDevBoards] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const { isAuthenticated } = useAuth0()
  const { userRegistered } = useContext(UserContext)

  useEffect(() => {
    const fetchDevBoards = async () => {
      try {
        setLoading(true)
        const data = await devBoardsApi.getAll()
        console.log('Fetched dev boards:', data)
        setDevBoards(data)
        setError(null)
      } catch (err) {
        console.error('Error fetching dev boards:', err)
        setError('Failed to fetch dev boards. Please try again.')
      } finally {
        setLoading(false)
      }
    }

    fetchDevBoards()
  }, [])

  if (loading) {
    return (
      <Container style={{ marginTop: '2em' }}>
        <Loader active>Loading dev boards...</Loader>
      </Container>
    )
  }

  if (error) {
    return (
      <Container style={{ marginTop: '2em' }}>
        <Message negative>
          <Message.Header>Error</Message.Header>
          <p>{error}</p>
          <Button onClick={() => window.location.reload()}>
            Retry
          </Button>
        </Message>
      </Container>
    )
  }

  return (
    <Container>
      <Header as='h1'>Development Boards</Header>
      
      {isAuthenticated && userRegistered && (
        <Button 
          as={Link} 
          to="/devboards/new" 
          primary 
          style={{ marginBottom: '2em' }}
        >
          Add New Dev Board
        </Button>
      )}

      {devBoards.length === 0 ? (
        <Message info>
          <Message.Header>No Dev Boards Found</Message.Header>
          <p>There are no development boards available at the moment.</p>
        </Message>
      ) : (
        <Grid columns={3} stackable>
          {devBoards.map(devBoard => (
            <Grid.Column key={devBoard.id} style={{ marginBottom: '1.5em' }}>
              <DevBoardCard devBoard={devBoard} />
            </Grid.Column>
          ))}
        </Grid>
      )}
    </Container>
  )
}

export default DevBoardsPage
