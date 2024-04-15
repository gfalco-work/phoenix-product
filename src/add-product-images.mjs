import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  UpdateCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "ProductTable";

awsXRay.captureAWS(aws);

export async function handler(event) {
  console.log('Received Step Functions event:', JSON.stringify(event, null, 2));

  const { productId, images } = event;

  let body;
  let statusCode = 200;
  try {
    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');

    subSegment.addAnnotation('product id', productId);
    subSegment.addMetadata('product', event);

    const command = new UpdateCommand({
      TableName: tableName,
      Key: {
        PK: 'PRODUCT#' + productId,
        SK: 'PRODUCT#' + productId
      },
      UpdateExpression:
          'set #images = :images',
      ExpressionAttributeNames: {
        '#images': 'images'
      },
      ExpressionAttributeValues: {
        ":images": images
      },
      ReturnValues: "NONE"
    });

    body = await dynamo.send(command);
    console.log(body);
    subSegment.close();
  } catch (err) {
    statusCode = 400;
    body = err.message;
  }
  const headers = {
    "Access-Control-Allow-Headers" : "Content-Type",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "OPTIONS,POST"
  };
  return {statusCode, body, headers};
}