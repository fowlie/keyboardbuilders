import React from 'react';
import { Card, Label, Icon } from 'semantic-ui-react';

function ControllerCard({ controller }) {
  if (!controller) {
    return null;
  }
  
  return (
    <Card className="controller-card">
      <Card.Content>
        <Card.Header>{controller.name}</Card.Header>
        <Card.Meta>
          {controller.manufacturer && (
            <Label size="tiny">
              <Icon name="microchip" />
              {controller.manufacturer}
            </Label>
          )}
        </Card.Meta>
        <Card.Description>
          {controller.chipset && (
            <div>
              <strong>Chipset:</strong> {controller.chipset}
            </div>
          )}
          
          {controller.url && (
            <div className="url-section" style={{ marginTop: '0.5em' }}>
              <a 
                href={controller.url} 
                target="_blank" 
                rel="noopener noreferrer"
              >
                <Icon name="external" />
                More Info
              </a>
            </div>
          )}
        </Card.Description>
      </Card.Content>
    </Card>
  );
}

export default ControllerCard; 