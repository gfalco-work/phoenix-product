import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "OnlineShop";

awsXRay.captureAWS(aws);

export async function handler(event) {
  console.log('Received Step Functions event:', JSON.stringify(event, null, 2));

  let statusCode = 200;
  let productId;
  try {
    const { product, image } = JSON.parse(event);
    console.log('Received product data:', product);

    productId = product.id;

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('product id', product.id);
    subSegment.addMetadata('product', product);

    await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'PRODUCT#' + product.id,
            SK: product.category,
            name: product.name,
            description: product.description,
            price: product.price
          },
        })
    );

    subSegment.close();
  } catch (err) {
    statusCode = 400;
  }
  const headers = {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST"
  };
  return {statusCode, productId, headers};
}