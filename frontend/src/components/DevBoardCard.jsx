import React from 'react';
import { Card, Label, Icon } from 'semantic-ui-react';
import { Link } from 'react-router-dom';
import SafeImage from './SafeImage';

function DevBoardCard({ devBoard }) {
  if (!devBoard) {
    return null;
  }
  
  return (
    <Card as={Link} to={`/devboards/${devBoard.id}`} className="dev-board-card">
      <Card.Content>
        <Card.Header>{devBoard.name}</Card.Header>
        <Card.Meta>
          {devBoard.wireless ? (
            <Label color="blue" size="tiny">
              <Icon name="wifi" />
              Wireless
            </Label>
          ) : (
            <Label size="tiny">
              <Icon name="plug" />
              Wired
            </Label>
          )}
        </Card.Meta>
        <Card.Description>
          {devBoard.controller && (
            <div>
              <strong>Controller:</strong> {devBoard.controller.name}
              {devBoard.controller.manufacturer && (
                <div><small>Manufacturer: {devBoard.controller.manufacturer}</small></div>
              )}
              {devBoard.controller.chipset && (
                <div><small>Chipset: {devBoard.controller.chipset}</small></div>
              )}
            </div>
          )}
          
          {devBoard.url && (
            <div className="url-section" style={{ marginTop: '0.5em' }}>
              <span 
                role="button"
                style={{ color: 'blue', textDecoration: 'underline', cursor: 'pointer' }}
                onClick={(e) => {
                  e.stopPropagation();
                  e.preventDefault();
                  window.open(devBoard.url, '_blank', 'noopener,noreferrer');
                }}
              >
                <Icon name="external" />
                More Info
              </span>
            </div>
          )}
        </Card.Description>
      </Card.Content>
    </Card>
  );
}

export default DevBoardCard; 