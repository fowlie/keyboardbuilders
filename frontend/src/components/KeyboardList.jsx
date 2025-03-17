import React from 'react'
import { useNavigate } from 'react-router-dom'
import { Container, Header, Segment } from 'semantic-ui-react'

function KeyboardList() {
  const navigate = useNavigate()

  const handleAddKeyboard = () => {
    navigate('/keyboards/new')
  }

  return (
    <Container>
      <Segment>
        <Header as='h2' floated='left'>
          Keyboard List
        </Header>
        <button 
          className="ui primary button"
          style={{ float: 'right' }}
          onClick={handleAddKeyboard}
          type="button"
        >
          Add Keyboard
        </button>
        <div style={{ clear: 'both' }}></div>
      </Segment>
    </Container>
  )
}

export default KeyboardList 